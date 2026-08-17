package io.stackblaze.dbeaver.ext.s3.fs;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.nio.NIOPath;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

public class RustfsPath extends NIOPath {

    @NotNull
    private final RustfsFileSystem fileSystem;

    public RustfsPath(@NotNull RustfsFileSystem fileSystem) {
        super(null, fileSystem);
        this.fileSystem = fileSystem;
    }

    public RustfsPath(@NotNull RustfsFileSystem fileSystem, @NotNull String path) {
        super(normalizePath(path), fileSystem);
        this.fileSystem = fileSystem;
    }

    private static String normalizePath(@NotNull String path) {
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    @Override
    public RustfsFileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * NIOPath.toString() returns the full URI, which the navigator and the
     * Cloud Storage panel use as the display name (getFileName().toString()).
     * Return the plain object path instead so files show as "inv-1001.json",
     * not "s3://connection-id/inv-1001.json". Kept consistent for both sides
     * of NIOPath's toString-based startsWith/endsWith comparisons.
     */
    @Override
    public String toString() {
        return CommonUtils.isEmpty(path) ? getConnectionId() : path;
    }

    @NotNull
    public String getConnectionId() {
        return fileSystem.getConnectionId();
    }

    @Nullable
    public String getObjectPath() {
        return path;
    }

    public boolean isConnectionRoot() {
        return CommonUtils.isEmpty(path);
    }

    public boolean isBucketPath() {
        String[] parts = pathParts();
        return parts.length == 1;
    }

    @Nullable
    public String getBucketName() {
        String[] parts = pathParts();
        if (parts.length == 0) {
            return null;
        }
        return parts[0];
    }

    @Nullable
    public String getObjectKey() {
        String[] parts = pathParts();
        if (parts.length <= 1) {
            return null;
        }
        return String.join("/", Arrays.copyOfRange(parts, 1, parts.length));
    }

    @Override
    public Path getRoot() {
        if (isConnectionRoot()) {
            return null;
        }
        return new RustfsPath(fileSystem);
    }

    @Override
    public Path getFileName() {
        String[] parts = pathParts();
        if (ArrayUtils.isEmpty(parts)) {
            return this;
        }
        return new RustfsPath(fileSystem, parts[parts.length - 1]);
    }

    @Override
    public Path getParent() {
        if (isConnectionRoot()) {
            return null;
        }
        String[] parts = pathParts();
        if (parts.length == 1) {
            return new RustfsPath(fileSystem);
        }
        return new RustfsPath(fileSystem, String.join(getFileSystem().getSeparator(), Arrays.copyOfRange(parts, 0, parts.length - 1)));
    }

    @Override
    public int getNameCount() {
        return pathParts().length;
    }

    @Override
    public Path normalize() {
        return this;
    }

    @Override
    public Path resolve(Path other) {
        RustfsPath otherPath = (RustfsPath) other;
        if (!otherPath.getConnectionId().equals(getConnectionId())) {
            throw new IllegalArgumentException("Cannot resolve path from another connection");
        }
        return resolve(otherPath.getObjectPath());
    }

    @Override
    public Path resolve(String other) {
        if (CommonUtils.isEmpty(other)) {
            return this;
        }
        return new RustfsPath(fileSystem, resolveString(other));
    }

    @Override
    public URI toUri() {
        var uriBuilder = new StringBuilder();
        uriBuilder.append(fileSystem.provider().getScheme())
            .append("://")
            .append(getConnectionId());

        if (!CommonUtils.isEmpty(path)) {
            uriBuilder.append(getFileSystem().getSeparator());
            uriBuilder.append(
                Arrays.stream(path.split(getFileSystem().getSeparator()))
                    .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8))
                    .collect(Collectors.joining(getFileSystem().getSeparator()))
            );
        }
        return URI.create(uriBuilder.toString());
    }

    @Override
    public boolean isAbsolute() {
        return true;
    }

    @Override
    public Path toAbsolutePath() {
        return this;
    }

    @Override
    public Path toRealPath(@NotNull LinkOption... options) throws IOException {
        return this;
    }

    /**
     * Every RustfsPath reports isAbsolute() == true, so Path's default
     * resolve() would return `other` wholesale — losing this path's prefix
     * (rename targets ended up at bucket level with an empty object key).
     * Resolve by concatenating object paths instead.
     */
    @Override
    public Path resolve(@NotNull Path other) {
        if (!(other instanceof RustfsPath otherPath)) {
            return other;
        }
        String otherObject = otherPath.getObjectPath();
        if (CommonUtils.isEmpty(otherObject)) {
            return this;
        }
        if (CommonUtils.isEmpty(path)) {
            return otherPath;
        }
        return new RustfsPath(fileSystem, path + getFileSystem().getSeparator() + otherObject);
    }

    @Override
    public Path relativize(@NotNull Path other) {
        var relativeUri = toUri().resolve(other.toUri());
        return new RustfsPath(fileSystem, relativeUri.getPath());
    }

    @Override
    public Path getName(int index) {
        String[] parts = pathParts();
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("Invalid index value: " + index);
        }
        return new RustfsPath(fileSystem, parts[index]);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        String[] parts = pathParts();
        if (beginIndex < 0 || beginIndex > parts.length) {
            throw new IllegalArgumentException("Invalid begin index value: " + beginIndex);
        }
        if (endIndex < 0 || endIndex > parts.length || endIndex < beginIndex) {
            throw new IllegalArgumentException("Invalid end index value: " + endIndex);
        }
        return new RustfsPath(fileSystem, String.join(getFileSystem().getSeparator(), Arrays.copyOfRange(parts, beginIndex, endIndex)));
    }

    @Override
    protected String resolveString(@NotNull String other) {
        if (CommonUtils.isEmpty(path)) {
            return normalizePath(other);
        }
        String separator = getFileSystem().getSeparator();
        String base = path.endsWith(separator) ? path : path + separator;
        return normalizePath(base + other);
    }

    @Override
    protected String[] pathParts() {
        if (CommonUtils.isEmpty(path)) {
            return new String[0];
        }
        return path.split(getFileSystem().getSeparator());
    }
}
