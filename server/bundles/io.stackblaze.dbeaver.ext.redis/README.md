# Stackblaze Redis / Valkey driver for CloudBeaver CE

Clean-room native (non-JDBC) datasource provider so CloudBeaver Community can
browse Redis/Valkey instances. DBeaver EE's Redis plugin is proprietary — this
bundle is an independent Stackblaze implementation.

## Driver id

`redis:redis` (provider `redis`, driver `redis`)

## Tree

```
connection
└── db0
    ├── key1 (string|hash|list|set|zset|stream)
    └── …
```

Keys implement `DBSDataContainer` so the Data tab renders a read-only result set
per type. Editing / TTL / pub-sub are out of scope for v1.

## Build notes

- Jedis is copied into `lib/` at Maven `generate-resources` (see `pom.xml`).
- JARs are also packaged under `deploy/drivers/redis/` via `server/drivers/redis`.
- Registered in `io.cloudbeaver.resources.drivers.base` and included in
  `io.cloudbeaver.server.feature`.
