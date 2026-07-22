package io.stackblaze.dbeaver.ext.redis.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;

public class RedisDataSourceInfo extends AbstractDataSourceInfo {

    private final String serverVersion;

    public RedisDataSourceInfo(String serverVersion) {
        this.serverVersion = serverVersion == null ? "Redis" : serverVersion;
    }

    @NotNull
    @Override
    public String getDatabaseProductName() {
        return "Redis";
    }

    @NotNull
    @Override
    public String getDatabaseProductVersion() {
        return serverVersion;
    }

    @NotNull
    @Override
    public String getDriverName() {
        return "Jedis (Stackblaze)";
    }

    @NotNull
    @Override
    public String getDriverVersion() {
        return "5.x";
    }

    @Override
    public String getSchemaTerm() {
        return "Database";
    }

    @Override
    public String getProcedureTerm() {
        return "";
    }

    @Override
    public String getCatalogTerm() {
        return "Database";
    }

    @Override
    public boolean supportsResultSetLimit() {
        return true;
    }

    @Override
    public boolean isReadOnlyData() {
        return true;
    }

    @Override
    public boolean isDynamicMetadata() {
        return true;
    }
}
