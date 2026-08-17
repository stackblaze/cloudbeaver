package io.stackblaze.dbeaver.ext.s3.model;

import io.minio.MinioClient;
import io.stackblaze.dbeaver.ext.s3.fs.RustfsS3ClientFactory;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPExclusiveResource;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.AbstractDataSource;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Minimal S3 / RustFS data source — validates connectivity via {@code listBuckets}.
 * Navigator children are buckets (leaves); object browsing goes through the
 * RustFS virtual file system.
 */
public class RustfsDataSource extends AbstractDataSource
    implements DBSInstance, DBSObjectContainer {

    private final DBPExclusiveResource exclusiveLock = new RustfsExclusiveResource();
    private final RustfsExecutionContext executionContext;
    private final RustfsDataSourceInfo info = new RustfsDataSourceInfo();
    private volatile MinioClient minioClient;
    private List<RustfsBucket> buckets;
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
    public DBCExecutionContext openIsolatedContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String purpose,
        @Nullable DBCExecutionContext initFrom
    ) {
        return new RustfsExecutionContext(this, purpose);
    }

    @NotNull
    @Override
    public DBPExclusiveResource getExclusiveLock() {
        return exclusiveLock;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) {
        // Bucket listing is lazy (getBuckets) — nothing to pre-load.
    }

    @NotNull
    public synchronized List<RustfsBucket> getBuckets(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (buckets != null) {
            return buckets;
        }
        monitor.subTask("List S3 buckets");
        List<RustfsBucket> list = new ArrayList<>();
        try {
            for (io.minio.messages.Bucket bucket : getMinioClient().listBuckets()) {
                String created = bucket.creationDate() != null ? bucket.creationDate().toString() : null;
                list.add(new RustfsBucket(this, bucket.name(), created));
            }
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Failed to list S3 buckets: " + e.getMessage(), e);
        }
        buckets = list;
        return buckets;
    }

    /** Property accessor used by the navigator tree (`property="buckets"`). */
    public List<RustfsBucket> getBuckets() {
        return buckets != null ? buckets : Collections.emptyList();
    }

    @Nullable
    @Override
    public Collection<? extends DBSObject> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getBuckets(monitor);
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        for (RustfsBucket bucket : getBuckets(monitor)) {
            if (bucket.getName().equals(childName)) {
                return bucket;
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return RustfsBucket.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getBuckets(monitor);
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
