package io.stackblaze.dbeaver.ext.mongodb;

import io.stackblaze.dbeaver.ext.mongodb.model.MongoDataSource;
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
 * Native (non-JDBC) MongoDB / FerretDB data source provider for CloudBeaver CE.
 * Clean-room Stackblaze implementation — not derived from DBeaver EE.
 */
public class MongoDataSourceProvider extends AbstractDataSourceProvider {

    public MongoDataSourceProvider() {
        super(MongoDataSource.class);
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
                // Fall through to the constructed mongodb:// URL.
            }
        }
        String host = CommonUtils.notEmpty(connectionInfo.getHostName());
        String port = CommonUtils.notEmpty(connectionInfo.getHostPort());
        String db = CommonUtils.notEmpty(connectionInfo.getDatabaseName());
        return "mongodb://" + host + ":" + port + "/" + db;
    }

    @NotNull
    @Override
    public MongoDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new MongoDataSource(monitor, container);
    }
}
