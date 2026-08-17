package io.stackblaze.dbeaver.ext.redis.model;

import io.stackblaze.dbeaver.ext.redis.RedisConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A Redis logical database (SELECT index). Children are keys discovered via SCAN.
 */
public class RedisDatabase implements DBSObject, DBSObjectContainer {

    private final RedisDataSource dataSource;
    private final int dbIndex;
    private List<RedisKey> keys;

    public RedisDatabase(@NotNull RedisDataSource dataSource, int dbIndex) {
        this.dataSource = dataSource;
        this.dbIndex = dbIndex;
    }

    @Property(viewable = true, order = 1)
    public int getDbIndex() {
        return dbIndex;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 2)
    public String getName() {
        return "db" + dbIndex;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 3)
    public String getDescription() {
        return "Redis database " + dbIndex;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public RedisDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    public synchronized List<RedisKey> getKeys(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (keys != null) {
            return keys;
        }
        List<RedisKey> found = new ArrayList<>();
        Jedis jedis = dataSource.getJedis();
        synchronized (jedis) {
            jedis.select(dbIndex);
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().count(RedisConstants.SCAN_COUNT);
            do {
                monitor.subTask("SCAN keys in db" + dbIndex);
                ScanResult<String> result = jedis.scan(cursor, params);
                cursor = result.getCursor();
                for (String keyName : result.getResult()) {
                    String type;
                    try {
                        type = jedis.type(keyName);
                    } catch (Exception e) {
                        type = "none";
                    }
                    found.add(new RedisKey(this, keyName, type));
                    if (found.size() >= RedisConstants.MAX_KEYS) {
                        break;
                    }
                }
            } while (!"0".equals(cursor) && found.size() < RedisConstants.MAX_KEYS && !monitor.isCanceled());
        }
        keys = found;
        return keys;
    }

    /**
     * Property accessor used by the navigator tree (`property="keys"`).
     * Returns null when not yet loaded so the tree treats children as lazy
     * (an empty list would make the node look childless forever).
     */
    @Nullable
    public List<RedisKey> getKeys() {
        if (keys == null && dataSource.isConnected()) {
            try {
                return getKeys(new VoidProgressMonitor());
            } catch (DBException e) {
                return null;
            }
        }
        return keys;
    }

    @Nullable
    @Override
    public Collection<? extends DBSObject> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getKeys(monitor);
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        for (RedisKey key : getKeys(monitor)) {
            if (key.getName().equals(childName)) {
                return key;
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return RedisKey.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        keys = null;
        getKeys(monitor);
    }
}
