# Stackblaze leftover-volume files driver for CloudBeaver CE

Native (non-JDBC) datasource so CloudBeaver can browse a leftover PVC
through the HTTP listing sidecar kubero-server mounts on the volume.

## Driver id

`files:http` (provider `files`, driver `http`)

## Tree

```
connection
├── wp-content/        (folder)
│   └── uploads/       (folder)
│       └── img.jpg    (file — Data tab shows name/size/preview)
└── wp-config.php      (file)
```

Connects to `http://{host}:{port}` with the connection password as a
Bearer token. Endpoints: `GET /health`, `GET /ls?path=`, `GET /cat?path=`.

## Build notes

- No extra JARs — uses `java.net.http.HttpClient`.
- Registered in `io.cloudbeaver.resources.drivers.base` and included in
  `io.cloudbeaver.server.feature`.
