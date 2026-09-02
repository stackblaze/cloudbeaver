package io.stackblaze.dbeaver.ext.files.model;

import io.stackblaze.dbeaver.ext.files.FilesConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Root leftover-volume data source. Children are the volume's top-level files.
 */
public class FilesDataSource extends AbstractDataSource
    implements DBSInstance, DBSObjectContainer {

    private final FilesExecutionContext executionContext;
    private final FilesExclusiveResource exclusiveLock = new FilesExclusiveResource();
    private final FilesDataSourceInfo info = new FilesDataSourceInfo();
    private FilesClient client;
    private FilesFolder root;
    private boolean connected;

    public FilesDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        super(container);
        this.executionContext = new FilesExecutionContext(this, "Main");
        connect(monitor);
    }

    private void connect(@NotNull DBRProgressMonitor monitor) throws DBException {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        String host = CommonUtils.notEmpty(cfg.getHostName());
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = CommonUtils.toInt(cfg.getHostPort(), FilesConstants.DEFAULT_PORT);
        String token = cfg.getUserPassword();
        monitor.subTask("Connect to volume files " + host + ":" + port);
        client = new FilesClient(host, port, token);
        client.ping();
        root = new FilesFolder(this, this, "/", "/");
        connected = true;
    }

    @NotNull
    FilesClient client() throws DBException {
        if (client == null || !connected) {
            throw new DBException("Volume files is not connected");
        }
        return client;
    }

    public boolean isConnected() {
        return connected && client != null;
    }

    /** Property accessor used by the navigator tree (`property="items"`). */
    @Nullable
    public List<DBSObject> getItems() {
        return root != null ? root.getItems() : null;
    }

    @NotNull
    @Override
    public FilesDataSourceInfo getInfo() {
        return info;
    }

    @NotNull
    @Override
    public SQLDialect getSQLDialect() {
        return FilesSQLDialect.INSTANCE;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (root != null) {
            root.getItems(monitor);
        }
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
        return new FilesExecutionContext(this, purpose);
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
        client = null;
        try {
            executionContext.close();
        } catch (Exception ignored) {
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
        return root == null ? Collections.emptyList() : root.getItems(monitor);
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        return root == null ? null : root.getChild(monitor, childName);
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return FilesFolder.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        if (root != null) {
            root.cacheStructure(monitor, scope);
        }
    }
}
