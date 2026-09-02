package io.stackblaze.dbeaver.ext.files.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.impl.AbstractSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

public class FilesSession extends AbstractSession {

    private final FilesExecutionContext context;

    public FilesSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull FilesExecutionContext context,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String taskTitle
    ) {
        super(monitor, purpose, taskTitle);
        this.context = context;
    }

    @NotNull
    @Override
    public FilesExecutionContext getExecutionContext() {
        return context;
    }

    @NotNull
    @Override
    public FilesDataSource getDataSource() {
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
            "SQL statements are not supported for volumes. Use the navigator to browse files."
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
