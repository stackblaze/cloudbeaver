package io.stackblaze.dbeaver.ext.s3.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceInfo;
import org.osgi.framework.Version;

public class RustfsDataSourceInfo extends AbstractDataSourceInfo {

    @NotNull
    @Override
    public String getDatabaseProductName() {
        return "S3";
    }

    @NotNull
    @Override
    public String getDatabaseProductVersion() {
        return "S3-compatible";
    }

    @NotNull
    @Override
    public Version getDatabaseVersion() {
        return new Version(1, 0, 0);
    }

    @NotNull
    @Override
    public String getDriverName() {
        return "MinIO SDK (Stackblaze)";
    }

    @NotNull
    @Override
    public String getDriverVersion() {
        return "8.5";
    }

    @Override
    public boolean isReadOnlyData() {
        return true;
    }
}
