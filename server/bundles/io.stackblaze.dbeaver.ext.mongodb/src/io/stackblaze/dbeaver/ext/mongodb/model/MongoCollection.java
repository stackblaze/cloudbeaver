package io.stackblaze.dbeaver.ext.mongodb.model;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCursor;
import io.stackblaze.dbeaver.ext.mongodb.MongoConstants;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
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

/**
 * A MongoDB collection. Implements DBSDataContainer so CloudBeaver's Data tab
 * can render documents (`find()`, skip/limit honoured) as a simple result set
 * (read-only): `_id` + the document as relaxed extended JSON.
 */
public class MongoCollection implements DBSObject, DBSDataContainer {

    private static final Log log = Log.getLog(MongoCollection.class);

    private static final JsonWriterSettings JSON = JsonWriterSettings.builder()
        .outputMode(JsonMode.RELAXED)
        .build();

    private final MongoDatabase database;
    private final String name;

    public MongoCollection(@NotNull MongoDatabase database, @NotNull String name) {
        this.database = database;
        this.name = name;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 2)
    public String getDescription() {
        return "MongoDB collection";
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
    public MongoDataSource getDataSource() {
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
        LocalStatement statement = new LocalStatement(
            session, "MONGO FIND " + database.getName() + "." + name);
        LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement);
        try {
            resultSet.addColumn("_id", DBPDataKind.STRING);
            resultSet.addColumn("document", DBPDataKind.STRING);

            com.mongodb.client.MongoCollection<Document> collection = getDataSource()
                .getClient()
                .getDatabase(database.getName())
                .getCollection(name);
            FindIterable<Document> find = collection.find();
            if (firstRow > 0) {
                find = find.skip((int) Math.min(firstRow, Integer.MAX_VALUE));
            }
            long limit = maxRows > 0 ? maxRows : MongoConstants.DEFAULT_MAX_DOCUMENTS;
            find = find.limit((int) Math.min(limit, Integer.MAX_VALUE));
            try (MongoCursor<Document> cursor = find.iterator()) {
                while (cursor.hasNext()) {
                    Document doc = cursor.next();
                    Object id = doc.get("_id");
                    resultSet.addRow(id == null ? "" : String.valueOf(id), doc.toJson(JSON));
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
            throw new DBCException(
                "Failed to read collection '" + database.getName() + "." + name + "': " + e.getMessage(), e);
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
            return getDataSource()
                .getClient()
                .getDatabase(database.getName())
                .getCollection(name)
                .estimatedDocumentCount();
        } catch (Exception e) {
            throw new DBCException(
                "Failed to count collection '" + database.getName() + "." + name + "'", e);
        }
    }
}
