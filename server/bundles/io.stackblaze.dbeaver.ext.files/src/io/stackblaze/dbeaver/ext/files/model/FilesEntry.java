package io.stackblaze.dbeaver.ext.files.model;

import io.stackblaze.dbeaver.ext.files.FilesConstants;
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

/** A file on the leftover volume. Data tab shows name, size, and a text preview. */
public class FilesEntry implements DBSObject, DBSDataContainer {

    private static final Log log = Log.getLog(FilesEntry.class);

    private final FilesDataSource dataSource;
    private final FilesFolder parent;
    private final String path;
    private final String name;
    private final long size;

    public FilesEntry(
        @NotNull FilesDataSource dataSource,
        @NotNull FilesFolder parent,
        @NotNull String path,
        @NotNull String name,
        long size
    ) {
        this.dataSource = dataSource;
        this.parent = parent;
        this.path = path;
        this.name = name;
        this.size = size;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public long getSize() {
        return size;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getPath() {
        return path;
    }

    @Nullable
    @Override
    public String getDescription() {
        return size + " bytes";
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public FilesDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    @Override
    public String[] getSupportedFeatures() {
        return new String[]{ FEATURE_DATA_SELECT, FEATURE_DATA_COUNT };
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
        LocalStatement statement = new LocalStatement(session, "FILES CAT " + path);
        LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement);
        try {
            resultSet.addColumn("name", DBPDataKind.STRING);
            resultSet.addColumn("size", DBPDataKind.NUMERIC);
            resultSet.addColumn("preview", DBPDataKind.STRING);
            String preview = dataSource.client().cat(path, FilesConstants.PREVIEW_CHARS);
            resultSet.addRow(name, size, preview);
            dataReceiver.fetchStart(session, resultSet, firstRow, maxRows);
            long rows = 0;
            while (resultSet.nextRow()) {
                dataReceiver.fetchRow(session, resultSet);
                rows++;
            }
            stats.setRowsFetched(rows);
            dataReceiver.fetchEnd(session, resultSet);
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBCException("Failed to read file '" + path + "': " + e.getMessage(), e);
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
    ) {
        return 1;
    }
}
