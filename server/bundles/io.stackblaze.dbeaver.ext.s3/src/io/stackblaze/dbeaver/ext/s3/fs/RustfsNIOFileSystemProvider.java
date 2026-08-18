package io.stackblaze.dbeaver.ext.s3.fs;

import io.cloudbeaver.model.WebConnectionInfo;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.Directive;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
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

import java.io.ByteArrayInputStream;
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

        boolean write = options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND);
        boolean readExisting = !write
            || options.contains(StandardOpenOption.APPEND)
            || !(options.contains(StandardOpenOption.TRUNCATE_EXISTING) || options.contains(StandardOpenOption.CREATE_NEW));

        byte[] initial = new byte[0];
        if (readExisting) {
            try (InputStream stream = getMinioClient().getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build()
            )) {
                initial = stream.readAllBytes();
            } catch (Exception e) {
                if (!write) {
                    throw new IOException("Failed to read S3 object: " + e.getMessage(), e);
                }
                // Writing a new object — nothing to pre-read.
            }
        }

        RustfsByteArrayChannel.WriteSink sink = write ? data -> putObject(bucket, key, data) : null;
        return new RustfsByteArrayChannel(initial, options, sink);
    }

    private void putObject(String bucket, String key, byte[] data) throws IOException {
        try {
            getMinioClient().putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to write S3 object: " + e.getMessage(), e);
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
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        RustfsPath s3Dir = (RustfsPath) dir;
        if (s3Dir.isConnectionRoot()) {
            throw new IOException("Cannot create the storage root");
        }
        try {
            if (s3Dir.isBucketPath()) {
                getMinioClient().makeBucket(MakeBucketArgs.builder().bucket(s3Dir.getBucketName()).build());
                return;
            }
            // S3 has no directories — create the conventional zero-byte marker.
            putObject(s3Dir.getBucketName(), s3Dir.getObjectKey() + "/", new byte[0]);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to create S3 directory: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        RustfsPath s3Path = (RustfsPath) path;
        if (s3Path.isConnectionRoot()) {
            throw new IOException("Cannot delete the storage root");
        }
        try {
            MinioClient client = getMinioClient();
            if (s3Path.isBucketPath()) {
                client.removeBucket(RemoveBucketArgs.builder().bucket(s3Path.getBucketName()).build());
                return;
            }
            String bucket = s3Path.getBucketName();
            String key = s3Path.getObjectKey();
            // Folder prefix: delete everything under it, then the marker/object itself.
            Iterable<Result<Item>> nested = client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(key + "/").recursive(true).build()
            );
            for (Result<Item> result : nested) {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(result.get().objectName()).build());
            }
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key + "/").build());
        } catch (Exception e) {
            throw new IOException("Failed to delete S3 object: " + e.getMessage(), e);
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        RustfsPath src = (RustfsPath) source;
        RustfsPath dst = (RustfsPath) target;
        if (src.isConnectionRoot() || dst.isConnectionRoot() || src.isBucketPath() || dst.isBucketPath()) {
            throw new IOException("Only S3 objects and folders can be copied");
        }
        if (CommonUtils.isEmpty(src.getObjectKey()) || CommonUtils.isEmpty(dst.getObjectKey())) {
            throw new IOException("Only S3 objects and folders can be copied");
        }
        try {
            if (isFolderObject(src)) {
                copyPrefix(src, dst);
                return;
            }
            copyObject(src.getBucketName(), src.getObjectKey(), dst.getBucketName(), dst.getObjectKey());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to copy S3 object: " + e.getMessage(), e);
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        copy(source, target, options);
        delete(source);
    }

    private boolean isFolderObject(@NotNull RustfsPath path) throws Exception {
        if (path.isConnectionRoot() || path.isBucketPath()) {
            return true;
        }
        String key = path.getObjectKey();
        if (CommonUtils.isEmpty(key)) {
            return false;
        }
        if (!listObjectKeys(path.getBucketName(), key.endsWith("/") ? key : key + "/").isEmpty()) {
            return true;
        }
        try {
            BasicFileAttributes attrs = readAttributes(path, BasicFileAttributes.class);
            return attrs != null && attrs.isDirectory();
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    private void copyPrefix(@NotNull RustfsPath src, @NotNull RustfsPath dst) throws Exception {
        String srcKey = src.getObjectKey();
        String dstKey = dst.getObjectKey();
        String srcPrefix = srcKey.endsWith("/") ? srcKey : srcKey + "/";
        String dstPrefix = dstKey.endsWith("/") ? dstKey : dstKey + "/";
        List<String> keys = listObjectKeys(src.getBucketName(), srcPrefix);
        boolean copiedMarker = copyObjectIfExists(src.getBucketName(), srcPrefix, dst.getBucketName(), dstPrefix);
        for (String key : keys) {
            if (key.equals(srcPrefix)) {
                continue;
            }
            String relative = key.startsWith(srcPrefix) ? key.substring(srcPrefix.length()) : key;
            copyObject(src.getBucketName(), key, dst.getBucketName(), dstPrefix + relative);
        }
        if (keys.isEmpty() && !copiedMarker) {
            putObject(dst.getBucketName(), dstPrefix, new byte[0]);
        }
    }

    private void copyObject(String srcBucket, String srcKey, String dstBucket, String dstKey) throws Exception {
        getMinioClient().copyObject(
            CopyObjectArgs.builder()
                .bucket(dstBucket)
                .object(dstKey)
                // RustFS rejects COPY-directive requests that carry metadata:
                // "Replacement metadata requires the REPLACE metadata directive"
                .metadataDirective(Directive.REPLACE)
                .source(CopySource.builder().bucket(srcBucket).object(srcKey).build())
                .build()
        );
    }

    private boolean copyObjectIfExists(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        try {
            copyObject(srcBucket, srcKey, dstBucket, dstKey);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NotNull
    private List<String> listObjectKeys(String bucket, String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = getMinioClient().listObjects(
            ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build()
        );
        for (Result<Item> result : results) {
            Item item = result.get();
            if (!item.isDir() && CommonUtils.isNotEmpty(item.objectName())) {
                keys.add(item.objectName());
            }
        }
        return keys;
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
