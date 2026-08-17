package io.stackblaze.dbeaver.ext.s3.fs;

import io.cloudbeaver.model.WebConnectionInfo;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.model.session.WebSessionWorkspace;
import io.stackblaze.dbeaver.ext.s3.RustfsConstants;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.fs.AbstractFileSystemProvider;
import org.jkiss.dbeaver.model.fs.DBFFileSystemContainer;
import org.jkiss.dbeaver.model.fs.DBFVirtualFileSystem;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.List;

public class RustfsVirtualFileSystemProvider extends AbstractFileSystemProvider {

    @NotNull
    @Override
    public DBFVirtualFileSystem[] getAvailableFileSystems(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBFFileSystemContainer fsContainer
    ) {
        if (!(fsContainer instanceof WebSessionWorkspace workspace)) {
            return new DBFVirtualFileSystem[0];
        }
        if (!(workspace.getWebSession() instanceof WebSession webSession)) {
            return new DBFVirtualFileSystem[0];
        }

        List<DBFVirtualFileSystem> fileSystems = new ArrayList<>();
        for (var project : webSession.getAccessibleProjects()) {
            for (WebConnectionInfo connection : project.getConnections()) {
                if (isS3Connection(connection)) {
                    fileSystems.add(new RustfsVirtualFileSystem(connection));
                }
            }
        }
        return fileSystems.toArray(DBFVirtualFileSystem[]::new);
    }

    private static boolean isS3Connection(@NotNull WebConnectionInfo connection) {
        String driverId = connection.getDriverId();
        if (driverId != null && driverId.contains(RustfsConstants.DRIVER_ID)) {
            return true;
        }
        var driver = connection.getDataSourceContainer().getDriver();
        return RustfsConstants.PROVIDER_ID.equals(driver.getProviderId());
    }
}
