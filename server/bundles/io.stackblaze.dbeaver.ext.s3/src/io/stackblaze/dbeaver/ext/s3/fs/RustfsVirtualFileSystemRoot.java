package io.stackblaze.dbeaver.ext.s3.fs;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.fs.DBFVirtualFileSystem;
import org.jkiss.dbeaver.model.fs.DBFVirtualFileSystemRoot;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.nio.file.Path;

public class RustfsVirtualFileSystemRoot implements DBFVirtualFileSystemRoot {

    @NotNull
    private final RustfsVirtualFileSystem fileSystem;
    @NotNull
    private final RustfsNIOFileSystemProvider nioProvider;

    public RustfsVirtualFileSystemRoot(
        @NotNull RustfsVirtualFileSystem fileSystem,
        @NotNull RustfsNIOFileSystemProvider nioProvider
    ) {
        this.fileSystem = fileSystem;
        this.nioProvider = nioProvider;
    }

    @NotNull
    @Override
    public String getName() {
        return fileSystem.getFileSystemDisplayName();
    }

    @NotNull
    @Override
    public DBFVirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    @NotNull
    @Override
    public String getRootId() {
        return fileSystem.getId();
    }

    @Override
    public DBPImage getRootIcon() {
        return null;
    }

    @NotNull
    @Override
    public Path getRootPath(DBRProgressMonitor monitor) {
        return new RustfsPath(new RustfsFileSystem(fileSystem.getId(), nioProvider));
    }
}
