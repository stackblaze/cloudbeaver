# Kubero / Stackblaze CloudBeaver profile

Deploy CloudBeaver with this config (or merge its `app` / `server` keys into your runtime `cloudbeaver.conf`) for addon Access sessions.

## Required settings

| Setting | Purpose |
|---------|---------|
| `enabledAuthProviders: ["reverseProxy"]` | Auto-login via `X-User` (no login form) |
| `supportsCustomConnections: false` | Users cannot add arbitrary databases |
| `enabledDrivers` | Postgres + MariaDB/MySQL only |
| `rootURI` / `serviceURI` under `/cb/` | Same-origin proxy from Kubero |

## Reverse-proxy headers

Kubero's `/cb` proxy injects:

- `X-User` — Kubero username / stable subject id
- `X-Team` — optional team id

Do **not** expose CloudBeaver directly to the internet without stripping client-supplied `X-User` / `X-Team`.

## Kubero env

See `kubero/server/.env.template` for `CLOUDBEAVER_*` variables used by the session handoff service.
