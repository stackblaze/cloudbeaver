package io.stackblaze.dbeaver.ext.redis;

import io.stackblaze.dbeaver.ext.redis.model.RedisDataSource;
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
 * Native (non-JDBC) Redis / Valkey data source provider for CloudBeaver CE.
 * Clean-room Stackblaze implementation — not derived from DBeaver EE.
 */
public class RedisDataSourceProvider extends AbstractDataSourceProvider {

    public RedisDataSourceProvider() {
        super(RedisDataSource.class);
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
            return DatabaseURL.generateUrlByTemplate(driver, connectionInfo);
        }
        String host = CommonUtils.notEmpty(connectionInfo.getHostName());
        String port = CommonUtils.notEmpty(connectionInfo.getHostPort());
        String db = CommonUtils.notEmpty(connectionInfo.getDatabaseName());
        return "redis://" + host + ":" + port + "/" + db;
    }

    @NotNull
    @Override
    public RedisDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new RedisDataSource(monitor, container);
    }
}
