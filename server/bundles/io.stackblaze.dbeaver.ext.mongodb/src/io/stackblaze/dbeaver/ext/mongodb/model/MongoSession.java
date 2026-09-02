package io.stackblaze.dbeaver.ext.mongodb.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.impl.AbstractSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * Minimal session — MongoDB browsing does not use SQL statements.
 */
public class MongoSession extends AbstractSession {

    private final MongoExecutionContext context;

    public MongoSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull MongoExecutionContext context,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String taskTitle
    ) {
        super(monitor, purpose, taskTitle);
        this.context = context;
    }

    @NotNull
    @Override
    public MongoExecutionContext getExecutionContext() {
        return context;
    }

    @NotNull
    @Override
    public MongoDataSource getDataSource() {
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
            "SQL statements are not supported for MongoDB. "
                + "Use the navigator to browse collections — the Data tab runs find()."
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
