# Stackblaze S3 / RustFS driver for CloudBeaver CE

Clean-room native (non-JDBC) datasource provider and virtual file system for
S3-compatible object storage (RustFS, MinIO, etc.).

## Driver id

`s3:rustfs` (provider `s3`, driver `rustfs`)

## Connection properties

| Property   | Description                          |
|------------|--------------------------------------|
| `pathStyle`| Path-style access (default `true`)   |
| `useSsl`   | Use HTTPS (default `false`)          |
| `region`   | AWS region string (default `us-east-1`) |

Access key / secret key map to username / password.

## Virtual file system

Provider id `rustfs-s3`, NIO scheme `s3`. Paths: `s3://{connectionId}/{bucket}/{key...}`.

One virtual FS is created per accessible S3 connection in the web session workspace.

## Build notes

- MinIO Java SDK is copied into `lib/` at Maven `generate-resources` (see `pom.xml`).
- Registered in `io.cloudbeaver.resources.drivers.base` and included in
  `io.cloudbeaver.server.feature`.
