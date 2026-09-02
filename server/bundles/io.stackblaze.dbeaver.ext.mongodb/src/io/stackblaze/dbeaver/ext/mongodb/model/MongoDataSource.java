package io.stackblaze.dbeaver.ext.mongodb.model;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.stackblaze.dbeaver.ext.mongodb.MongoConstants;
import org.bson.Document;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPExclusiveResource;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.AbstractDataSource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root MongoDB data source. Acts as both DBPDataSource and the sole DBSInstance.
 * Uses the official MongoDB Java driver, which also speaks to FerretDB /
 * DocumentDB (pg_documentdb) — both implement the MongoDB wire protocol.
 */
public class MongoDataSource extends AbstractDataSource
    implements DBSInstance, DBSObjectContainer {

    private static final Log log = Log.getLog(MongoDataSource.class);

    private final MongoExecutionContext executionContext;
    private final MongoExclusiveResource exclusiveLock = new MongoExclusiveResource();
    private MongoDataSourceInfo info;
    private volatile MongoClient client;
    private List<MongoDatabase> databases;
    private volatile boolean connected;

    public MongoDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        super(container);
        this.executionContext = new MongoExecutionContext(this, "Main");
        connect(monitor);
    }

    private void connect(@NotNull DBRProgressMonitor monitor) throws DBException {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        String hostName = CommonUtils.notEmpty(cfg.getHostName());
        final String host = hostName.isEmpty() ? "localhost" : hostName;
        final int port = CommonUtils.toInt(cfg.getHostPort(), MongoConstants.DEFAULT_PORT);
        String user = CommonUtils.notEmpty(cfg.getUserName());
        String password = CommonUtils.notEmpty(cfg.getUserPassword());
        String database = CommonUtils.notEmpty(cfg.getDatabaseName());
        String authSource = CommonUtils.toString(
            cfg.getProviderProperty(MongoConstants.PROP_AUTH_SOURCE), "").trim();
        if (authSource.isEmpty()) {
            authSource = MongoConstants.DEFAULT_AUTH_SOURCE;
        }

        monitor.subTask("Connect to MongoDB " + host + ":" + port);
        try {
            MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applicationName("CloudBeaver (Stackblaze)")
                .applyToClusterSettings(cs -> cs
                    .hosts(Collections.singletonList(new ServerAddress(host, port)))
                    .serverSelectionTimeout(15_000, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(ss -> ss
                    .connectTimeout(15_000, TimeUnit.MILLISECONDS)
                    .readTimeout(60_000, TimeUnit.MILLISECONDS));
            if (!user.isEmpty()) {
                // SCRAM-SHA-256 is the one mechanism FerretDB v2 supports, and
                // every supported MongoDB (4.0+) speaks it as well.
                builder.credential(MongoCredential.createScramSha256Credential(
                    user, authSource, password.toCharArray()));
            }
            client = MongoClients.create(builder.build());

            String pingDb = database.isEmpty() ? authSource : database;
            client.getDatabase(pingDb).runCommand(new Document("ping", 1));

            String version = "MongoDB";
            try {
                Document buildInfo = client.getDatabase(pingDb).runCommand(new Document("buildInfo", 1));
                String v = buildInfo.getString("version");
                if (v != null && !v.isEmpty()) {
                    version = v;
                }
                Object ferret = buildInfo.get("ferretdb");
                if (ferret instanceof Document ferretDoc) {
                    Object fv = ferretDoc.get("version");
                    version = version + " (FerretDB" + (fv == null ? "" : " " + fv) + ")";
                }
            } catch (Exception e) {
                log.debug("Could not read MongoDB buildInfo: " + e.getMessage());
            }
            this.info = new MongoDataSourceInfo(version);
            this.connected = true;
        } catch (Exception e) {
            closeClient();
            throw new DBException("Failed to connect to MongoDB at " + host + ":" + port, e);
        }
    }

    @NotNull
    synchronized MongoClient getClient() throws DBException {
        if (client == null || !connected) {
            throw new DBException("MongoDB is not connected");
        }
        return client;
    }

    public boolean isConnected() {
        return connected && client != null;
    }

    @NotNull
    @Override
    public MongoDataSourceInfo getInfo() {
        return info != null ? info : new MongoDataSourceInfo("MongoDB");
    }

    @NotNull
    @Override
    public SQLDialect getSQLDialect() {
        return MongoSQLDialect.INSTANCE;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        getDatabases(monitor);
    }

    @NotNull
    public synchronized List<MongoDatabase> getDatabases(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (databases != null) {
            return databases;
        }
        List<MongoDatabase> list = new ArrayList<>();
        String database = CommonUtils.notEmpty(
            container.getActualConnectionConfiguration().getDatabaseName());
        if (!database.isEmpty()) {
            // Scoped connection (the provisioner passes the tenant database):
            // show exactly that database. FerretDB tenants may not be allowed
            // to run listDatabases anyway.
            list.add(new MongoDatabase(this, database));
        } else {
            monitor.subTask("List MongoDB databases");
            try {
                List<String> names = new ArrayList<>();
                for (String name : getClient().listDatabaseNames()) {
                    names.add(name);
                    if (names.size() >= MongoConstants.MAX_DATABASES) {
                        break;
                    }
                }
                Collections.sort(names);
                for (String name : names) {
                    list.add(new MongoDatabase(this, name));
                }
            } catch (DBException e) {
                throw e;
            } catch (Exception e) {
                throw new DBException("Failed to list MongoDB databases: " + e.getMessage(), e);
            }
        }
        databases = list;
        return databases;
    }

    /** Property accessor used by the navigator tree (`property="databases"`). */
    public List<MongoDatabase> getDatabases() {
        return databases != null ? databases : Collections.emptyList();
    }

    @NotNull
    @Override
    public DBCExecutionContext getDefaultContext(@NotNull DBRProgressMonitor monitor, boolean meta) {
        return executionContext;
    }

    @NotNull
    @Override
    public DBCExecutionContext[] getAllContexts() {
        return new DBCExecutionContext[]{executionContext};
    }

    @NotNull
    @Override
    public DBCExecutionContext openIsolatedContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String purpose,
        @Nullable DBCExecutionContext initFrom
    ) {
        return new MongoExecutionContext(this, purpose);
    }

    @NotNull
    @Override
    public DBSInstance getDefaultInstance() {
        return this;
    }

    @NotNull
    @Override
    public Collection<? extends DBSInstance> getAvailableInstances() {
        return Collections.singletonList(this);
    }

    @Override
    public void shutdown(@NotNull DBRProgressMonitor monitor) {
        connected = false;
        closeClient();
        try {
            executionContext.close();
        } catch (Exception ignored) {
        }
    }

    private void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing MongoClient: " + e.getMessage());
            }
            client = null;
        }
    }

    @NotNull
    @Override
    public DBPExclusiveResource getExclusiveLock() {
        return exclusiveLock;
    }

    @Nullable
    @Override
    public Collection<? extends DBSObject> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getDatabases(monitor);
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        for (MongoDatabase db : getDatabases(monitor)) {
            if (db.getName().equals(childName)) {
                return db;
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return MongoDatabase.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getDatabases(monitor);
    }
}
