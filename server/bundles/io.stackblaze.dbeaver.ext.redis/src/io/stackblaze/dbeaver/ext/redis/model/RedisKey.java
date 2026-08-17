package io.stackblaze.dbeaver.ext.redis.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.impl.local.LocalResultSet;
import org.jkiss.dbeaver.model.impl.local.LocalStatement;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.Tuple;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Redis key. Implements DBSDataContainer so CloudBeaver's Data tab can
 * render the value as a simple result set (read-only).
 */
public class RedisKey implements DBSObject, DBSDataContainer {

    private static final Log log = Log.getLog(RedisKey.class);

    private final RedisDatabase database;
    private final String keyName;
    private final String redisType;

    public RedisKey(@NotNull RedisDatabase database, @NotNull String keyName, @Nullable String redisType) {
        this.database = database;
        this.keyName = keyName;
        this.redisType = redisType == null ? "none" : redisType;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return keyName;
    }

    @Property(viewable = true, order = 2)
    public String getRedisType() {
        return redisType;
    }

    @Nullable
    @Override
    public String getDescription() {
        return redisType + " key";
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return database;
    }

    @NotNull
    @Override
    public RedisDataSource getDataSource() {
        return database.getDataSource();
    }

    @NotNull
    @Override
    public String[] getSupportedFeatures() {
        return new String[]{
            FEATURE_DATA_SELECT,
            FEATURE_DATA_COUNT,
        };
    }

    @NotNull
    @Override
    public DBCStatistics readData(
        @Nullable DBCExecutionSource source,
        @NotNull DBCSession session,
        @NotNull DBDDataReceiver dataReceiver,
        @Nullable DBDDataFilter dataFilter,
        long firstRow,
        long maxRows,
        long flags,
        int fetchSize
    ) throws DBException {
        DBCStatistics stats = new DBCStatistics();
        long start = System.currentTimeMillis();
        LocalStatement statement = new LocalStatement(session, "REDIS READ " + keyName);
        LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement);
        try {
            Jedis jedis = getDataSource().getJedis();
            synchronized (jedis) {
                jedis.select(database.getDbIndex());
                switch (redisType.toLowerCase()) {
                    case "string" -> {
                        resultSet.addColumn("value", DBPDataKind.STRING);
                        String val = jedis.get(keyName);
                        resultSet.addRow(val == null ? "" : val);
                    }
                    case "hash" -> {
                        resultSet.addColumn("field", DBPDataKind.STRING);
                        resultSet.addColumn("value", DBPDataKind.STRING);
                        Map<String, String> map = jedis.hgetAll(keyName);
                        for (Map.Entry<String, String> e : map.entrySet()) {
                            resultSet.addRow(e.getKey(), e.getValue());
                        }
                    }
                    case "list" -> {
                        resultSet.addColumn("index", DBPDataKind.NUMERIC);
                        resultSet.addColumn("value", DBPDataKind.STRING);
                        long end = maxRows > 0 ? firstRow + maxRows - 1 : -1;
                        List<String> list = jedis.lrange(keyName, firstRow, end);
                        for (int i = 0; i < list.size(); i++) {
                            resultSet.addRow(firstRow + i, list.get(i));
                        }
                    }
                    case "set" -> {
                        resultSet.addColumn("member", DBPDataKind.STRING);
                        Set<String> members = jedis.smembers(keyName);
                        long skipped = 0;
                        long fetched = 0;
                        for (String m : members) {
                            if (skipped++ < firstRow) continue;
                            resultSet.addRow(m);
                            fetched++;
                            if (maxRows > 0 && fetched >= maxRows) break;
                        }
                    }
                    case "zset" -> {
                        resultSet.addColumn("member", DBPDataKind.STRING);
                        resultSet.addColumn("score", DBPDataKind.NUMERIC);
                        long end = maxRows > 0 ? firstRow + maxRows - 1 : -1;
                        List<Tuple> tuples = jedis.zrangeWithScores(keyName, firstRow, end);
                        for (Tuple t : tuples) {
                            resultSet.addRow(t.getElement(), t.getScore());
                        }
                    }
                    case "stream" -> {
                        resultSet.addColumn("id", DBPDataKind.STRING);
                        resultSet.addColumn("fields", DBPDataKind.STRING);
                        List<StreamEntry> entries = jedis.xrange(keyName, "-", "+");
                        long skipped = 0;
                        long fetched = 0;
                        for (StreamEntry entry : entries) {
                            if (skipped++ < firstRow) continue;
                            resultSet.addRow(entry.getID().toString(), String.valueOf(entry.getFields()));
                            fetched++;
                            if (maxRows > 0 && fetched >= maxRows) break;
                        }
                    }
                    default -> {
                        resultSet.addColumn("type", DBPDataKind.STRING);
                        resultSet.addColumn("note", DBPDataKind.STRING);
                        resultSet.addRow(redisType, "Unsupported Redis type for tabular view");
                    }
                }
            }

            dataReceiver.fetchStart(session, resultSet, firstRow, maxRows);
            long rowsFetched = 0;
            while (resultSet.nextRow()) {
                dataReceiver.fetchRow(session, resultSet);
                rowsFetched++;
            }
            stats.setRowsFetched(rowsFetched);
            dataReceiver.fetchEnd(session, resultSet);
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBCException("Failed to read Redis key '" + keyName + "': " + e.getMessage(), e);
        } finally {
            stats.setExecuteTime(System.currentTimeMillis() - start);
            try {
                resultSet.close();
            } catch (Exception e) {
                log.debug(e);
            }
            try {
                dataReceiver.close();
            } catch (Exception e) {
                log.debug(e);
            }
        }
        return stats;
    }

    @Override
    public long countData(
        @NotNull DBCExecutionSource source,
        @NotNull DBCSession session,
        @Nullable DBDDataFilter dataFilter,
        long flags
    ) throws DBException {
        try {
            Jedis jedis = getDataSource().getJedis();
            synchronized (jedis) {
                jedis.select(database.getDbIndex());
                return switch (redisType.toLowerCase()) {
                    case "string" -> 1;
                    case "hash" -> jedis.hlen(keyName);
                    case "list" -> jedis.llen(keyName);
                    case "set" -> jedis.scard(keyName);
                    case "zset" -> jedis.zcard(keyName);
                    case "stream" -> jedis.xlen(keyName);
                    default -> 0;
                };
            }
        } catch (Exception e) {
            throw new DBCException("Failed to count Redis key '" + keyName + "'", e);
        }
    }
}
