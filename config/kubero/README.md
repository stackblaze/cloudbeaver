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

- Image: `ghcr.io/stackblaze/cloudbeaver`
- Namespace: `cloudbeaver` (ClusterIP only)
- Kubero env (helm `cloudbeaver.enabled`): `CLOUDBEAVER_INTERNAL_URL`,
  `CLOUDBEAVER_ADMIN_*`, `CLOUDBEAVER_PUBLIC_PATH=/cb`

Do **not** expose CloudBeaver with a public Ingress in prod — that bypasses
scoped handoff and risks cross-tenant visibility for admin logins.

## Config profile

[`cloudbeaver.conf`](./cloudbeaver.conf) / [`deploy/k8s/cloudbeaver.conf`](../../deploy/k8s/cloudbeaver.conf):

- `reverseProxy` + `local` auth
- `supportsCustomConnections: false`
- Drivers: Postgres + MariaDB/MySQL only
- Anonymous access off
