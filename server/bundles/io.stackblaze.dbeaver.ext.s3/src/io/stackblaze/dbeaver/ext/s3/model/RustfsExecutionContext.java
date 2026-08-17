package io.stackblaze.dbeaver.ext.s3.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCInvalidatePhase;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.AbstractExecutionContext;
import org.jkiss.dbeaver.model.impl.AbstractSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

public class RustfsExecutionContext extends AbstractExecutionContext<RustfsDataSource, RustfsDataSource> {

    private volatile boolean connected = true;

    public RustfsExecutionContext(@NotNull RustfsDataSource dataSource, @NotNull String purpose) {
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
        return new AbstractSession(monitor, this, purpose) {
        };
    }

    @Override
    public void checkContextAlive(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isConnected()) {
            throw new DBCException("S3 connection is closed");
        }
    }

    @Override
    public void invalidateContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCInvalidatePhase phase
    ) {
        // noop
    }

    @Override
    public void close() {
        connected = false;
        closeContext();
    }
}
