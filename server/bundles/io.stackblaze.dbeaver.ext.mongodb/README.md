# Stackblaze MongoDB / FerretDB driver

Native (non-JDBC) MongoDB data source for CloudBeaver CE, built on the official
`org.mongodb:mongodb-driver-sync`. Clean-room Stackblaze implementation,
mirroring `io.stackblaze.dbeaver.ext.redis` — not derived from DBeaver EE.

- Tree: connection → databases → collections. When the connection names a
  database, only that database is shown (FerretDB tenants, communal DocumentDB).
- Data tab on a collection runs `find()` (skip/limit honoured) and renders
  `_id` + the document as relaxed extended JSON. Read-only.
- Auth: SCRAM-SHA-256 when a username is set (the only mechanism FerretDB v2
  supports; fine for real MongoDB too). `authSource` driver property, default
  `admin`.
- Works against MongoDB (KuberoMongoDB addon) and FerretDB / DocumentDB
  (`pg_documentdb`) — both speak the wire protocol the official driver uses.
