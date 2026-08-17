package io.stackblaze.dbeaver.ext.s3.fs;

import io.cloudbeaver.model.WebConnectionInfo;
import io.stackblaze.dbeaver.ext.s3.RustfsConstants;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.fs.AbstractVirtualFileSystem;
import org.jkiss.dbeaver.model.fs.DBFVirtualFileSystemRoot;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public class RustfsVirtualFileSystem extends AbstractVirtualFileSystem {

    @NotNull
    private final WebConnectionInfo connection;
    @NotNull
    private final RustfsNIOFileSystemProvider nioProvider;

    public RustfsVirtualFileSystem(@NotNull WebConnectionInfo connection) {
        this.connection = connection;
        this.nioProvider = new RustfsNIOFileSystemProvider(connection);
    }

    @NotNull
    public WebConnectionInfo getConnection() {
        return connection;
    }

    @NotNull
    public RustfsNIOFileSystemProvider getNioProvider() {
        return nioProvider;
    }

    @NotNull
    @Override
    public String getFileSystemDisplayName() {
        return connection.getDataSourceContainer().getName();
    }

    @NotNull
    @Override
    public String getType() {
        return RustfsConstants.FS_TYPE;
    }

    @Override
    public String getDescription() {
        return "S3 / RustFS object storage";
    }

    @Override
    public DBPImage getIcon() {
        return null;
    }

    @NotNull
    @Override
    public String getId() {
        return connection.getId();
    }

    @NotNull
    @Override
    public String getProviderId() {
        return RustfsConstants.FS_PROVIDER_ID;
    }

    @NotNull
    @Override
    public Path getPathByURI(@NotNull DBRProgressMonitor monitor, @NotNull URI uri) {
        return nioProvider.getPath(uri);
    }

    @NotNull
    @Override
    public List<? extends DBFVirtualFileSystemRoot> getRootFolders(DBRProgressMonitor monitor) throws DBException {
        return List.of(new RustfsVirtualFileSystemRoot(this, nioProvider));
    }
}
