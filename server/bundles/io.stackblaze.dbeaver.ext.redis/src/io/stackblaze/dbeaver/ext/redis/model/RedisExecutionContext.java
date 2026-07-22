package io.stackblaze.dbeaver.ext.redis.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.impl.AbstractExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

public class RedisExecutionContext extends AbstractExecutionContext<RedisDataSource, RedisDataSource> {

    private volatile boolean connected = true;

    public RedisExecutionContext(@NotNull RedisDataSource dataSource, String purpose) {
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
        return new RedisSession(monitor, this, purpose, task);
    }

    @Override
    public void checkContextValidity(@NotNull DBRProgressMonitor monitor) throws DBCException {
        if (!isConnected()) {
            throw new DBCException("Redis connection is closed", this);
        }
    }

    @Override
    public void invalidateContext(@NotNull DBRProgressMonitor monitor, boolean closeOnFailure)
        throws DBException {
        // Jedis reconnect is handled lazily on next command.
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
