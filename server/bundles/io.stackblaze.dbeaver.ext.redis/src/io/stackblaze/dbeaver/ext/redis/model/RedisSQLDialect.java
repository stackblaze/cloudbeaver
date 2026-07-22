package io.stackblaze.dbeaver.ext.redis.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.sql.SQLDialect;

/**
 * Minimal dialect so CloudBeaver can attach a Redis connection.
 * Redis is not SQL — browsing uses the object navigator / data viewer.
 */
public class RedisSQLDialect extends BasicSQLDialect {

    public static final RedisSQLDialect INSTANCE = new RedisSQLDialect();

    public RedisSQLDialect() {
    }

    @NotNull
    @Override
    public String getDialectId() {
        return "redis";
    }

    @NotNull
    @Override
    public String getDialectName() {
        return "Redis";
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
