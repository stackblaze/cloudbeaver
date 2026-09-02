package io.stackblaze.dbeaver.ext.files;

import io.stackblaze.dbeaver.ext.files.model.FilesDataSource;
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
 * Native (non-JDBC) leftover-volume file browser for CloudBeaver CE.
 */
public class FilesDataSourceProvider extends AbstractDataSourceProvider {

    public FilesDataSourceProvider() {
        super(FilesDataSource.class);
    }

    @Override
    public long getFeatures() {
        return FEATURE_CATALOGS;
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
                // Fall through to the constructed http URL.
            }
        }
        String host = CommonUtils.notEmpty(connectionInfo.getHostName());
        String port = CommonUtils.notEmpty(connectionInfo.getHostPort());
        return "http://" + host + ":" + port;
    }

    @NotNull
    @Override
    public FilesDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new FilesDataSource(monitor, container);
    }
}
