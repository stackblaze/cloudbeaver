package io.stackblaze.dbeaver.ext.redis.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.impl.AbstractSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * Minimal session — Redis browsing does not use SQL statements.
 */
public class RedisSession extends AbstractSession {

    private final RedisExecutionContext context;

    public RedisSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull RedisExecutionContext context,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String taskTitle
    ) {
        super(monitor, purpose, taskTitle);
        this.context = context;
    }

    @NotNull
    @Override
    public RedisExecutionContext getExecutionContext() {
        return context;
    }

    @NotNull
    @Override
    public RedisDataSource getDataSource() {
        return context.getDataSource();
    }

    @NotNull
    @Override
    public DBCStatement prepareStatement(
        @NotNull DBCStatementType type,
        @NotNull String query,
        boolean scrollable,
        boolean updatable,
        boolean returnGeneratedKeys
    ) throws DBCException {
        throw new DBCException(
            "SQL statements are not supported for Redis. Use the navigator to browse keys."
        );
    }

    @Override
    public void cancelBlock(
        @NotNull DBRProgressMonitor monitor,
        @Nullable Thread blockThread
    ) {
        // no-op
    }
}
