package io.stackblaze.dbeaver.ext.redis.model;

import io.stackblaze.dbeaver.ext.redis.RedisConstants;
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
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Root Redis data source. Acts as both DBPDataSource and the sole DBSInstance.
 */
public class RedisDataSource extends AbstractDataSource
    implements DBSInstance, DBSObjectContainer {

    private static final Log log = Log.getLog(RedisDataSource.class);

    private final RedisExecutionContext executionContext;
    private final RedisExclusiveResource exclusiveLock = new RedisExclusiveResource();
    private RedisDataSourceInfo info;
    private volatile Jedis jedis;
    private List<RedisDatabase> databases;
    private boolean connected;

    public RedisDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        super(container);
        this.executionContext = new RedisExecutionContext(this, "Main");
        connect(monitor);
    }

    private void connect(@NotNull DBRProgressMonitor monitor) throws DBException {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        String host = CommonUtils.notEmpty(cfg.getHostName());
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = CommonUtils.toInt(cfg.getHostPort(), RedisConstants.DEFAULT_PORT);
        String password = cfg.getUserPassword();
        int dbIndex = CommonUtils.toInt(cfg.getDatabaseName(), RedisConstants.DEFAULT_DB);

        monitor.subTask("Connect to Redis " + host + ":" + port);
        try {
            JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password(CommonUtils.isEmpty(password) ? null : password)
                .database(dbIndex)
                .timeoutMillis(15_000)
                .build();
            jedis = new Jedis(new HostAndPort(host, port), clientConfig);
            String pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new DBException("Unexpected PING response: " + pong);
            }
            String version = "Redis";
            try {
                String infoStr = jedis.info("server");
                for (String line : infoStr.split("\n")) {
                    if (line.startsWith("redis_version:") || line.startsWith("valkey_version:")) {
                        version = line.substring(line.indexOf(':') + 1).trim();
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("Could not read Redis INFO server: " + e.getMessage());
            }
            this.info = new RedisDataSourceInfo(version);
            this.connected = true;
        } catch (DBException e) {
            closeJedis();
            throw e;
        } catch (Exception e) {
            closeJedis();
            throw new DBException("Failed to connect to Redis at " + host + ":" + port, e);
        }
    }

    synchronized Jedis getJedis() throws DBException {
        if (jedis == null || !connected) {
            throw new DBException("Redis is not connected");
        }
        return jedis;
    }

    public boolean isConnected() {
        return connected && jedis != null;
    }

    @NotNull
    @Override
    public RedisDataSourceInfo getInfo() {
        return info != null ? info : new RedisDataSourceInfo("Redis");
    }

    @NotNull
    @Override
    public SQLDialect getSQLDialect() {
        return RedisSQLDialect.INSTANCE;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        getDatabases(monitor);
    }

    @NotNull
    public synchronized List<RedisDatabase> getDatabases(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (databases != null) {
            return databases;
        }
        List<RedisDatabase> list = new ArrayList<>();
        int dbIndex = CommonUtils.toInt(
            container.getActualConnectionConfiguration().getDatabaseName(),
            RedisConstants.DEFAULT_DB
        );
        list.add(new RedisDatabase(this, dbIndex));
        databases = list;
        return databases;
    }

    /** Property accessor used by the navigator tree (`property="databases"`). */
    public List<RedisDatabase> getDatabases() {
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
        return new RedisExecutionContext(this, purpose);
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
        closeJedis();
        try {
            executionContext.close();
        } catch (Exception ignored) {
        }
    }

    private void closeJedis() {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception e) {
                log.debug("Error closing Jedis: " + e.getMessage());
            }
            jedis = null;
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
        for (RedisDatabase db : getDatabases(monitor)) {
            if (db.getName().equals(childName)) {
                return db;
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return RedisDatabase.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getDatabases(monitor);
    }
}
