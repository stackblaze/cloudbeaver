package io.stackblaze.dbeaver.ext.s3;

import io.stackblaze.dbeaver.ext.s3.model.RustfsDataSource;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DatabaseURL;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.impl.AbstractDataSourceProvider;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

/**
 * Native (non-JDBC) S3 / RustFS data source provider for CloudBeaver CE.
 */
public class RustfsDataSourceProvider extends AbstractDataSourceProvider {

    public RustfsDataSourceProvider() {
        super(RustfsDataSource.class);
    }

    @Override
    public long getFeatures() {
        return 0;
    }

    @Override
    public boolean providesDriverClasses(@NotNull DBPDriver driver) {
        return false;
    }

    @NotNull
    @Override
    public String getConnectionURL(@NotNull DBPDriver driver, @NotNull DBPConnectionConfiguration connectionInfo) {
        String template = driver.getSampleURL();
        if (!CommonUtils.isEmpty(template)) {
            try {
                return DatabaseURL.generateUrlByTemplate(driver, connectionInfo);
            } catch (DBException e) {
                // Fall through to the constructed http(s):// URL.
            }
        }
        String host = CommonUtils.notEmpty(connectionInfo.getHostName());
        String port = CommonUtils.notEmpty(connectionInfo.getHostPort());
        boolean useSsl = CommonUtils.toBoolean(
            connectionInfo.getProviderProperty(RustfsConstants.PROP_USE_SSL),
            false
        );
        return (useSsl ? "https" : "http") + "://" + host + ":" + port;
    }

    @NotNull
    @Override
    public RustfsDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new RustfsDataSource(monitor, container);
    }
}
