package io.stackblaze.dbeaver.ext.files.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;
import org.osgi.framework.Version;

public class FilesDataSourceInfo extends AbstractDataSourceInfo {

    @NotNull
    @Override
    public String getDatabaseProductName() {
        return "Volume files";
    }

    @NotNull
    @Override
    public String getDatabaseProductVersion() {
        return "1";
    }

    @NotNull
    @Override
    public Version getDatabaseVersion() {
        return new Version(1, 0, 0);
    }

    @NotNull
    @Override
    public String getDriverName() {
        return "HTTP files (Stackblaze)";
    }

    @NotNull
    @Override
    public String getDriverVersion() {
        return "1.x";
    }

    @Override
    public String getSchemaTerm() {
        return "Folder";
    }

    @Override
    public String getProcedureTerm() {
        return "";
    }

    @Override
    public String getCatalogTerm() {
        return "Volume";
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
