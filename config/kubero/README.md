# Stackblaze communal CloudBeaver

One shared CloudBeaver instance for **all Stackblaze users**. It is not a
per-addon deploy and not a public self-serve login.

## Access model

```
User (Kubero UI) → Access tab → Open database
  → POST /api/apps/.../db/cloudbeaver/session
  → Kubero provisions CB connection + ACL for that user + addon only
  → one-time handoff → https://api.stackblaze.cloud/cb
  → Kubero proxy injects X-User → CloudBeaver reverseProxy login
  → Navigator shows only granted connection(s)
```

| Concern | Rule |
|---------|------|
| Who can open | Authenticated Kubero users with `app:read` on the addon |
| What they see | Only connections Kubero granted for their identity |
| Entry URL | `https://api.stackblaze.cloud/cb` (via handoff) — **not** a public Ingress |
| Credentials | Addon Secret (same as `/db/access`); no user paste into Adminer |

## Deploy

Two deployments run this image:

| Where | Entry | Deployed by |
|-------|-------|-------------|
| Legacy central instance (kubero cluster, ns `cloudbeaver`) | `https://api.stackblaze.cloud/cb` via the Kubero proxy | this repo's `deploy.yaml` (release → `kubectl set image`) |
| **Per-region instance** (services cluster, ns `cloudbeaver`, plus `cloudbeaver-dev` on us-west-1) | `https://db.<region>.stackblaze.cloud` behind the edge sidecar | `stackblaze/region-provisioner` — `cloudbeaver.yml` / `cloudbeaver-dev.yml`, image pinned in `ansible/group_vars/all.yml` (`cloudbeaver_image`) |

- Image: `ghcr.io/stackblaze/cloudbeaver:<tag>` — tags are `v25.2.0-sb.N`, minted by
  `auto-tag-on-main.yaml` on every `devel`/`main` push and built from source by
  `docker-release.yaml` (`deploy/docker/stackblaze/Dockerfile`).
- Namespace: `cloudbeaver` (ClusterIP only)
- Kubero env (helm `cloudbeaver.enabled`): `CLOUDBEAVER_INTERNAL_URL`,
  `CLOUDBEAVER_ADMIN_*`, `CLOUDBEAVER_PUBLIC_PATH=/cb`

Do **not** expose CloudBeaver with a public Ingress in prod — that bypasses
scoped handoff and risks cross-tenant visibility for admin logins.

### What the image needs from the pod

- **It runs as `USER dbeaver` (UID/GID 8978)**, unlike `dbeaver/cloudbeaver`
  which runs as root. The workspace volume must be writable by that GID
  (`securityContext.fsGroup: 8978`). With the wrong fsGroup the server cannot
  write `workspace/` and never listens on 8978 — the symptom is a 502 from
  whatever fronts it (this took every region down on 2026-08-30).
- The baked `conf/cloudbeaver.conf` hardcodes `rootURI: /cb`. A deployment that
  serves it at `/` overrides that in `workspace/.data/.cloudbeaver.runtime.conf`
  (`server.rootURI`, `server.serviceURI`) — the runtime file is merged over the
  main one on start, so no rebuild is needed (this is what the regional install
  does).
- The workspace is disposable: connections are provisioned per session by
  kubero-server and users are reverse-proxy identities. When rolling BACK to an
  older build, drop the workspace PVC instead of debugging an H2 downgrade.

## Config profile

[`cloudbeaver.conf`](./cloudbeaver.conf) / [`deploy/k8s/cloudbeaver.conf`](../../deploy/k8s/cloudbeaver.conf):

- `reverseProxy` + `local` auth
- `supportsCustomConnections: false`
- Anonymous access off
- `enabledDrivers` — the drivers kubero-server hands out sessions for
  (`cloudbeaver-access.service.ts` driverId map):

| Add-on | driverId | Provider |
|--------|----------|----------|
| PostgreSQL (CNPG `Cluster`, communal logical DBs) | `postgresql:postgres-jdbc` | upstream |
| MariaDB / MySQL | `mysql:mariaDB`, `mysql:mysql8` | upstream |
| **Valkey / Redis** (`Valkey`, `Redis`, `RedisCluster`, `KuberoAddonRedis`) | `redis:redis` | **this fork** — `server/bundles/io.stackblaze.dbeaver.ext.redis` (native, Jedis 5.2; `PING` on connect, `INFO server` for the version, keys under `db<N>` in the navigator). Upstream CE has no Redis provider (Enterprise-only), so the community image can never open a Valkey add-on. |
| ClickHouse | `clickhouse:com_clickhouse` | upstream |
| S3 (RustFS `Tenant`) | `s3:rustfs` | this fork — `io.stackblaze.dbeaver.ext.rustfs` |
| DuckDB companion for S3 | `duckdb:duckdb_jdbc` | upstream (embedded; needs `enabledDrivers`) |

The runtime gate wins over this file once a runtime config exists on the
workspace — see [`deploy/k8s/ensure-drivers-enabled.mjs`](../../deploy/k8s/ensure-drivers-enabled.mjs).
The regional install asserts every required driver is present **and enabled**
after each rollout (`cloudbeaver_required_drivers` in region-provisioner) and,
for the dev instance, opens a real `redis:redis` connection against a throwaway
Valkey pod.
