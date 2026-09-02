package io.stackblaze.dbeaver.ext.mongodb.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCInvalidatePhase;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.AbstractExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

public class MongoExecutionContext extends AbstractExecutionContext<MongoDataSource, MongoDataSource> {

    private volatile boolean connected = true;

    public MongoExecutionContext(@NotNull MongoDataSource dataSource, String purpose) {
        super(dataSource, purpose);
    }

    @Override
    public boolean isConnected() {
        return connected && getDataSource().isConnected();
    }

    @NotNull
    @Override
    public DBCSession openSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String task
    ) {
        return new MongoSession(monitor, this, purpose, task);
    }

    @Override
    public void checkContextAlive(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isConnected()) {
            throw new DBCException("MongoDB connection is closed");
        }
    }

    @Override
    public void invalidateContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCInvalidatePhase phase
    ) throws DBException {
        // The MongoClient pool reconnects lazily on the next command.
    }

    @Override
    public void close() {
        connected = false;
        closeContext();
    }

    @Nullable
    public VoidProgressMonitor voidMonitor() {
        return new VoidProgressMonitor();
    }
}
