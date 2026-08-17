package io.stackblaze.dbeaver.ext.s3.fs;

import io.cloudbeaver.model.WebConnectionInfo;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import io.stackblaze.dbeaver.ext.s3.RustfsConstants;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.nio.NIOFileBasicAttribute;
import org.jkiss.dbeaver.model.nio.NIOFileSystemProvider;
import org.jkiss.dbeaver.model.nio.NIOUtils;
import org.jkiss.utils.CommonUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RustfsNIOFileSystemProvider extends NIOFileSystemProvider {

    @NotNull
    private final WebConnectionInfo connection;
    private volatile MinioClient minioClient;

    public RustfsNIOFileSystemProvider(@NotNull WebConnectionInfo connection) {
        this.connection = connection;
    }

    @NotNull
    public WebConnectionInfo getConnection() {
        return connection;
    }

    @NotNull
    public MinioClient getMinioClient() {
        if (minioClient == null) {
            synchronized (this) {
                if (minioClient == null) {
                    DBPConnectionConfiguration cfg = connection.getDataSourceContainer().getActualConnectionConfiguration();
                    minioClient = RustfsS3ClientFactory.createClient(cfg);
                }
            }
        }
        return minioClient;
    }

    @Override
    public String getScheme() {
        return RustfsConstants.NIO_SCHEME;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        return getFileSystem(uri);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        validateUri(uri);
        String connectionId = uri.getAuthority();
        if (CommonUtils.isEmpty(connectionId)) {
            throw new FileSystemNotFoundException("Connection id not specified in URI: " + uri);
        }
        return new RustfsFileSystem(connectionId, this);
    }

    @Override
    public Path getPath(URI uri) {
        validateUri(uri);
        String connectionId = uri.getAuthority();
        if (CommonUtils.isEmpty(connectionId)) {
            throw new IllegalArgumentException("Connection id not specified in URI: " + uri);
        }
        RustfsFileSystem fs = new RustfsFileSystem(connectionId, this);
        String resourcePath = uri.getPath();
        if (CommonUtils.isNotEmpty(resourcePath)) {
            resourcePath = URLDecoder.decode(resourcePath, StandardCharsets.UTF_8);
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
        }
        if (CommonUtils.isEmpty(resourcePath)) {
            return new RustfsPath(fs);
        }
        return new RustfsPath(fs, resourcePath);
    }

    @Override
    public RustfsByteArrayChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
        RustfsPath s3Path = (RustfsPath) path;
        if (s3Path.isConnectionRoot() || s3Path.isBucketPath()) {
            throw new IllegalArgumentException("Cannot open channel for a folder");
        }
        String bucket = s3Path.getBucketName();
        String key = s3Path.getObjectKey();
        if (CommonUtils.isEmpty(bucket) || CommonUtils.isEmpty(key)) {
            throw new FileNotFoundException("Invalid S3 object path: " + path);
        }
        try (InputStream stream = getMinioClient().getObject(
            GetObjectArgs.builder().bucket(bucket).object(key).build()
        )) {
            return new RustfsByteArrayChannel(stream.readAllBytes(), options);
        } catch (Exception e) {
            throw new IOException("Failed to read S3 object: " + e.getMessage(), e);
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
        throws IOException {
        RustfsPath s3Dir = (RustfsPath) dir;
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                try {
                    return listChildren(s3Dir).iterator();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to list S3 path: " + e.getMessage(), e);
                }
            }

            @Override
            public void close() {
                // noop
            }
        };
    }

    @NotNull
    private List<Bucket> listBuckets() throws IOException {
        try {
            return getMinioClient().listBuckets();
        } catch (Exception e) {
            throw new IOException("Failed to list S3 buckets: " + e.getMessage(), e);
        }
    }

    @NotNull
    private List<Path> listChildren(@NotNull RustfsPath dir) throws IOException {
        List<Path> children = new ArrayList<>();
        MinioClient client = getMinioClient();
        String separator = dir.getFileSystem().getSeparator();

        if (dir.isConnectionRoot()) {
            for (Bucket bucket : listBuckets()) {
                children.add(new RustfsPath(dir.getFileSystem(), bucket.name()));
            }
            return children;
        }

        String bucket = dir.getBucketName();
        if (CommonUtils.isEmpty(bucket)) {
            return children;
        }

        String listPrefix = CommonUtils.notEmpty(dir.getObjectKey());
        if (!listPrefix.isEmpty() && !listPrefix.endsWith("/")) {
            listPrefix = listPrefix + "/";
        }

        Iterable<Result<Item>> results = client.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(listPrefix)
                .delimiter("/")
                .recursive(false)
                .build()
        );

        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Result<Item> result : results) {
            try {
                Item item = result.get();
                String name = item.objectName();
                if (CommonUtils.isEmpty(name)) {
                    continue;
                }
                if (!listPrefix.isEmpty() && name.startsWith(listPrefix)) {
                    name = name.substring(listPrefix.length());
                }
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                int slash = name.indexOf('/');
                if (slash >= 0) {
                    name = name.substring(0, slash);
                }
                if (name.isEmpty() || !seen.add(name)) {
                    continue;
                }
                String childPath = dir.isBucketPath()
                    ? bucket + separator + name
                    : NIOUtils.resolve(separator, dir.getObjectPath(), name);
                children.add(new RustfsPath(dir.getFileSystem(), childPath));
            } catch (Exception e) {
                throw new IOException("Failed to list S3 objects: " + e.getMessage(), e);
            }
        }
        return children;
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException("Creating S3 directories is not supported");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException("Deleting S3 objects is not supported");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return path.toString().equals(path2.toString());
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        RustfsPath s3Path = (RustfsPath) path;
        if (s3Path.isConnectionRoot()) {
            listBuckets();
            return;
        }
        readAttributes(path, BasicFileAttributes.class);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null;
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
        throws IOException {
        if (type != BasicFileAttributes.class) {
            return null;
        }
        RustfsPath s3Path = (RustfsPath) path;
        if (s3Path.isConnectionRoot()) {
            return type.cast(new RustfsFolderAttribute(FileTime.fromMillis(System.currentTimeMillis())));
        }
        if (s3Path.isBucketPath()) {
            String bucket = s3Path.getBucketName();
            boolean exists = listBuckets().stream().anyMatch(b -> bucket.equals(b.name()));
            if (!exists) {
                throw new FileNotFoundException("Bucket not found: " + bucket);
            }
            return type.cast(new RustfsFolderAttribute(FileTime.fromMillis(System.currentTimeMillis())));
        }

        String bucket = s3Path.getBucketName();
        String key = s3Path.getObjectKey();
        Iterable<Result<Item>> results = getMinioClient().listObjects(
            ListObjectsArgs.builder().bucket(bucket).prefix(key).maxKeys(1).build()
        );
        Iterator<Result<Item>> iterator = results.iterator();
        if (!iterator.hasNext()) {
            throw new FileNotFoundException("Object not found: " + path);
        }
        try {
            Item item = iterator.next().get();
            if (item.isDir()) {
                return type.cast(new RustfsFolderAttribute(itemModified(item)));
            }
            return type.cast(new RustfsObjectAttribute(item));
        } catch (Exception e) {
            throw new IOException("Failed to read S3 attributes: " + e.getMessage(), e);
        }
    }

    /** Folder entries (common prefixes) carry no lastModified — treat as epoch. */
    private static FileTime itemModified(Item item) {
        var modified = item.lastModified();
        return FileTime.fromMillis(modified != null ? modified.toInstant().toEpochMilli() : 0L);
    }

    private static final class RustfsFolderAttribute extends NIOFileBasicAttribute {
        private final FileTime modified;

        private RustfsFolderAttribute(FileTime modified) {
            this.modified = modified;
        }

        @Override
        public FileTime lastModifiedTime() {
            return modified;
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        @Override
        public long size() {
            return 0;
        }
    }

    private static final class RustfsObjectAttribute extends NIOFileBasicAttribute {
        private final Item item;

        private RustfsObjectAttribute(Item item) {
            this.item = item;
        }

        @Override
        public FileTime lastModifiedTime() {
            return itemModified(item);
        }

        @Override
        public boolean isDirectory() {
            return item.isDir();
        }

        @Override
        public long size() {
            return item.size();
        }
    }
}
