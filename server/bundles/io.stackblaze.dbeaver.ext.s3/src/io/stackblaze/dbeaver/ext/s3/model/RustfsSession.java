package io.stackblaze.dbeaver.ext.s3.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.impl.AbstractSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/** S3 browsing does not use SQL statements. */
public class RustfsSession extends AbstractSession {

    private final RustfsExecutionContext context;

    public RustfsSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull RustfsExecutionContext context,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String taskTitle
    ) {
        super(monitor, purpose, taskTitle);
        this.context = context;
    }

    @NotNull
    @Override
    public RustfsExecutionContext getExecutionContext() {
        return context;
    }

    @NotNull
    @Override
    public RustfsDataSource getDataSource() {
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
            "SQL statements are not supported for S3. Use Cloud Storage to browse objects."
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
