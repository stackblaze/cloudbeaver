package io.stackblaze.dbeaver.ext.s3.fs;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.nio.NIOFileSystem;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;

public class RustfsFileSystem extends NIOFileSystem {

    @NotNull
    private final String connectionId;
    @NotNull
    private final RustfsNIOFileSystemProvider provider;

    public RustfsFileSystem(
        @NotNull String connectionId,
        @NotNull RustfsNIOFileSystemProvider provider
    ) {
        this.connectionId = connectionId;
        this.provider = provider;
    }

    @NotNull
    public String getConnectionId() {
        return connectionId;
    }

    @NotNull
    public RustfsNIOFileSystemProvider rustfsProvider() {
        return provider;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        // noop
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(new RustfsPath(this));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of();
    }

    @NotNull
    @Override
    public Path getPath(@NotNull String first, @NotNull String... more) {
        if (CommonUtils.isEmpty(first)) {
            throw new IllegalArgumentException("Empty path");
        }
        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(provider().getScheme())
            .append("://")
            .append(connectionId);
        uriBuilder.append(getSeparator()).append(first);
        if (!ArrayUtils.isEmpty(more)) {
            uriBuilder.append(getSeparator()).append(String.join(getSeparator(), more));
        }
        return provider().getPath(URI.create(uriBuilder.toString()));
    }
}
