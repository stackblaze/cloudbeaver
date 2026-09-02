package io.stackblaze.dbeaver.ext.mongodb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;
import org.osgi.framework.Version;

public class MongoDataSourceInfo extends AbstractDataSourceInfo {

    private final String serverVersion;
    private final Version databaseVersion;

    public MongoDataSourceInfo(String serverVersion) {
        this.serverVersion = serverVersion == null ? "MongoDB" : serverVersion;
        this.databaseVersion = parseVersion(this.serverVersion);
    }

    @NotNull
    private static Version parseVersion(@NotNull String raw) {
        // MongoDB versions look like "7.0.14" or "8.0.4"; FerretDB reports a
        // compatible version plus a suffix. Fall back safely.
        String digits = raw.replaceAll("[^0-9.].*$", "").replaceAll("^[^0-9]+", "");
        if (digits.isEmpty()) {
            return new Version(0, 0, 0);
        }
        try {
            return new Version(digits);
        } catch (IllegalArgumentException e) {
            String[] parts = digits.split("\\.");
            int major = parts.length > 0 ? safeInt(parts[0]) : 0;
            int minor = parts.length > 1 ? safeInt(parts[1]) : 0;
            int micro = parts.length > 2 ? safeInt(parts[2]) : 0;
            return new Version(major, minor, micro);
        }
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NotNull
    @Override
    public String getDatabaseProductName() {
        return "MongoDB";
    }

    @NotNull
    @Override
    public String getDatabaseProductVersion() {
        return serverVersion;
    }

    @NotNull
    @Override
    public Version getDatabaseVersion() {
        return databaseVersion;
    }

    @NotNull
    @Override
    public String getDriverName() {
        return "MongoDB Java Driver (Stackblaze)";
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
