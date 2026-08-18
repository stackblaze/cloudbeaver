#!/usr/bin/env node
/*
 * Reconcile CloudBeaver's *runtime* driver gate with the drivers this
 * deployment intends to offer.
 *
 * Why this exists: cloudbeaver.conf (our ConfigMap) is loaded first, then
 * workspace/.data/.cloudbeaver.runtime.conf is loaded over it. Once anything
 * has written a runtime config, `enabledDrivers` / `disabledDrivers` in the
 * ConfigMap are dead letters — the PVC wins, and a driver we declared as
 * enabled in git can still be refused at connect time with "Driver disabled".
 *
 * Embedded drivers (DuckDB, SQLite, H2) are the sharp edge: CloudBeaver
 * disables them by default and only re-enables them via `enabledDrivers`, and
 * CBServerConfigurationMapper.updateDisabledDriversConfig() strips any id that
 * appears in the incoming disabled list out of the enabled list. Removing an
 * embedded driver from `disabledDrivers` is therefore the *only* supported way
 * to turn it on — the mapper adds it back to `enabledDrivers` for us.
 *
 * The write is a full configureServer call (exactly what the admin UI's Save
 * does), so every other server setting is read back and echoed unchanged. The
 * script refuses to run if it cannot read the current config, and verifies the
 * result afterwards.
 *
 * Usage (against a port-forward, or in-cluster):
 *   node ensure-drivers-enabled.mjs --url http://127.0.0.1:18978/cb/api/gql
 *   node ensure-drivers-enabled.mjs --apply          # actually write
 *   node ensure-drivers-enabled.mjs --drivers duckdb:duckdb_jdbc,s3:rustfs
 *
 * Defaults to a dry run: it prints what it would change and exits 0.
 */

const args = process.argv.slice(2);

function flag(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i === -1 ? fallback : args[i + 1];
}

const GQL_URL = flag('url', process.env.CB_GQL_URL || 'http://127.0.0.1:18978/cb/api/gql');
const ADMIN_USER = flag('user', process.env.CLOUDBEAVER_ADMIN_USER || 'cbadmin');
const ADMIN_TEAM = flag('team', process.env.CLOUDBEAVER_ADMIN_TEAM || 'admin');
const APPLY = args.includes('--apply');

/** Drivers this deployment must be able to connect with. */
const REQUIRED_DRIVERS = flag('drivers', 'duckdb:duckdb_jdbc,generic:duckdb_jdbc')
  .split(',')
  .map(s => s.trim())
  .filter(Boolean);

const SERVER_CONFIG_FIELDS = `
  name
  anonymousAccessEnabled
  supportsCustomConnections
  resourceManagerEnabled
  secretManagerEnabled
  publicCredentialsSaveEnabled
  adminCredentialsSaveEnabled
  sessionExpireTime
  enabledAuthProviders
  enabledFeatures
  supportedHosts
  forceHttps
  bindSessionToIp
  disabledDrivers
  configurationMode
`;

let cookie = '';

async function gql(query, variables = {}, headers = {}) {
  const res = await fetch(GQL_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}), ...headers },
    body: JSON.stringify({ query, variables }),
  });
  const setCookie = res.headers.getSetCookie?.() ?? [];
  if (setCookie.length > 0) {
    cookie = setCookie.map(c => c.split(';')[0]).join('; ');
  }
  const body = await res.json();
  if (body.errors?.length) {
    throw new Error(body.errors.map(e => e.message).join('; '));
  }
  return body.data;
}

async function readConfig() {
  const data = await gql(`query { serverConfig { ${SERVER_CONFIG_FIELDS} } }`);
  const config = data?.serverConfig;
  if (!config || typeof config.name !== 'string') {
    throw new Error('could not read serverConfig — refusing to write a config I cannot echo back');
  }
  return config;
}

async function main() {
  await gql(`mutation { result: openSession(defaultLocale: "en") { createTime } }`, {}, {
    'X-User': ADMIN_USER,
    'X-Team': ADMIN_TEAM,
  });

  const before = await readConfig();
  const disabled = before.disabledDrivers ?? [];
  const offenders = REQUIRED_DRIVERS.filter(id => disabled.includes(id));

  if (offenders.length === 0) {
    console.log(`OK: none of [${REQUIRED_DRIVERS.join(', ')}] are in disabledDrivers — nothing to do.`);
    return;
  }

  const nextDisabled = disabled.filter(id => !REQUIRED_DRIVERS.includes(id));
  console.log(`Will enable: ${offenders.join(', ')}`);
  console.log(`disabledDrivers: ${disabled.length} -> ${nextDisabled.length} entries`);

  if (before.configurationMode) {
    throw new Error('server is in configuration mode — refusing to touch it');
  }
  if (!APPLY) {
    console.log('\nDry run. Re-run with --apply to write this change.');
    return;
  }

  // Echo every field back unchanged; only disabledDrivers differs. Omitting a
  // field is not neutral — the mapper reads all of them and would happily
  // blank the server name (which flips the server into configuration mode) or
  // empty enabledAuthProviders (which enables *every* provider).
  // configureServer is declared under `extend type Query` in the admin schema,
  // not Mutation — sending it as a mutation fails with FieldUndefined.
  await gql(
    `query configureServer($configuration: ServerConfigInput!) {
      configureServer(configuration: $configuration)
    }`,
    {
      configuration: {
        serverName: before.name,
        sessionExpireTime: before.sessionExpireTime,
        anonymousAccessEnabled: before.anonymousAccessEnabled,
        customConnectionsEnabled: before.supportsCustomConnections,
        resourceManagerEnabled: before.resourceManagerEnabled,
        secretManagerEnabled: before.secretManagerEnabled,
        publicCredentialsSaveEnabled: before.publicCredentialsSaveEnabled,
        adminCredentialsSaveEnabled: before.adminCredentialsSaveEnabled,
        enabledAuthProviders: before.enabledAuthProviders,
        enabledFeatures: before.enabledFeatures,
        supportedHosts: before.supportedHosts,
        forceHttps: before.forceHttps,
        bindSessionToIp: before.bindSessionToIp,
        disabledDrivers: nextDisabled,
      },
    },
  );

  const after = await readConfig();
  const drift = Object.keys(before).filter(
    key => key !== 'disabledDrivers' && JSON.stringify(before[key]) !== JSON.stringify(after[key]),
  );

  console.log(`\ndisabledDrivers now: ${JSON.stringify(after.disabledDrivers)}`);
  if (drift.length > 0) {
    console.error(`WARNING: unintended changes to ${drift.join(', ')} — review the admin config.`);
    process.exitCode = 1;
    return;
  }
  const stillDisabled = REQUIRED_DRIVERS.filter(id => (after.disabledDrivers ?? []).includes(id));
  if (stillDisabled.length > 0) {
    console.error(`FAILED: still disabled: ${stillDisabled.join(', ')}`);
    process.exitCode = 1;
    return;
  }
  console.log('OK: drivers enabled, every other setting unchanged.');
}

main().catch(err => {
  console.error(`ensure-drivers-enabled: ${err.message}`);
  process.exit(1);
});
