package io.stackblaze.dbeaver.ext.mongodb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.sql.SQLDialect;

/**
 * Minimal dialect so CloudBeaver can attach a MongoDB connection.
 * MongoDB is not SQL — browsing uses the object navigator / data viewer.
 */
public class MongoSQLDialect extends BasicSQLDialect {

    public static final MongoSQLDialect INSTANCE = new MongoSQLDialect();

    public MongoSQLDialect() {
    }

    @NotNull
    @Override
    public String getDialectId() {
        return "mongodb";
    }

    @NotNull
    @Override
    public String getDialectName() {
        return "MongoDB";
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
