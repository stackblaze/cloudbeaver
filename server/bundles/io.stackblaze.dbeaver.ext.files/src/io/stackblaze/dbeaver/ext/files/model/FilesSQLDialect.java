package io.stackblaze.dbeaver.ext.files.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.sql.SQLDialect;

/** Minimal dialect so CloudBeaver can attach a files connection. */
public class FilesSQLDialect extends BasicSQLDialect {

    public static final FilesSQLDialect INSTANCE = new FilesSQLDialect();

    public FilesSQLDialect() {
    }

    @NotNull
    @Override
    public String getDialectId() {
        return "files";
    }

    @NotNull
    @Override
    public String getDialectName() {
        return "Files";
    }

    @Override
    public boolean supportsAliasInSelect() {
        return false;
    }

    @Override
    public boolean supportsOrderBy() {
        return false;
    }

    @Override
    public boolean isDelimiterAfterQuery() {
        return false;
    }

    @Override
    public String[] getExecuteKeywords() {
        return new String[0];
    }

    @NotNull
    public static SQLDialect getDialect() {
        return INSTANCE;
    }

    @SuppressWarnings("unused")
    public void initDriverSettings(DBCExecutionContext context) {
        // no-op
    }
}
