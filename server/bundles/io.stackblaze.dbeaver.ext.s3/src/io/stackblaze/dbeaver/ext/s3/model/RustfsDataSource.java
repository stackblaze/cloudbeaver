package io.stackblaze.dbeaver.ext.s3.model;

import io.minio.MinioClient;
import io.stackblaze.dbeaver.ext.s3.fs.RustfsS3ClientFactory;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPExclusiveResource;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.AbstractDataSource;
import org.jkiss.dbeaver.model.impl.SimpleExclusiveResource;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.struct.DBSInstance;

import java.util.Collection;
import java.util.Collections;

/**
 * Minimal S3 / RustFS data source — validates connectivity via {@code listBuckets}.
 */
public class RustfsDataSource extends AbstractDataSource implements DBSInstance {

    private final DBPExclusiveResource exclusiveLock = new SimpleExclusiveResource();
    private final RustfsExecutionContext executionContext;
    private final RustfsDataSourceInfo info = new RustfsDataSourceInfo();
    private volatile MinioClient minioClient;
    private boolean connected;

    public RustfsDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        super(container);
        this.executionContext = new RustfsExecutionContext(this, "Main");
        connect(monitor);
    }

    private void connect(@NotNull DBRProgressMonitor monitor) throws DBException {
        DBPConnectionConfiguration cfg = container.getActualConnectionConfiguration();
        monitor.subTask("Connect to S3 " + cfg.getHostName());
        try {
            minioClient = RustfsS3ClientFactory.createClient(cfg);
            minioClient.listBuckets();
            connected = true;
        } catch (Exception e) {
            closeClient();
            throw new DBException("Failed to connect to S3 endpoint at " + cfg.getHostName(), e);
        }
    }

    public boolean isConnected() {
        return connected && minioClient != null;
    }

    @NotNull
    public MinioClient getMinioClient() throws DBException {
        if (!isConnected()) {
            throw new DBException("S3 is not connected");
        }
        return minioClient;
    }

    @NotNull
    @Override
    public RustfsDataSourceInfo getInfo() {
        return info;
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

    @NotNull
    @Override
    public SQLDialect getSQLDialect() {
        return BasicSQLDialect.INSTANCE;
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
    public DBPExclusiveResource getExclusiveLock() {
        return exclusiveLock;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) {
        // noop
    }

    @Override
    public void shutdown(@NotNull DBRProgressMonitor monitor) {
        connected = false;
        closeClient();
        executionContext.close();
    }

    private void closeClient() {
        minioClient = null;
    }
}
