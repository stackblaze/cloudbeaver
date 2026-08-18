package io.stackblaze.dbeaver.ext.s3.fs;

import io.cloudbeaver.model.WebConnectionInfo;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.DeleteBucketEncryptionArgs;
import io.minio.DeleteBucketNotificationArgs;
import io.minio.DeleteObjectTagsArgs;
import io.minio.Directive;
import io.minio.GetBucketEncryptionArgs;
import io.minio.GetBucketNotificationArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetBucketTagsArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectTagsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketEncryptionArgs;
import io.minio.SetBucketNotificationArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.SetBucketTagsArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.SetObjectTagsArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.Result;
import io.minio.messages.Bucket;
import io.minio.messages.EventType;
import io.minio.messages.Item;
import io.minio.messages.NotificationConfiguration;
import io.minio.messages.QueueConfiguration;
import io.minio.messages.SseConfiguration;
import io.minio.messages.SseConfigurationRule;
import io.minio.messages.Tags;
import io.minio.messages.VersioningConfiguration;
import io.cloudbeaver.model.fs.FsBucketAdmin;
import io.cloudbeaver.model.fs.FsBucketEncryption;
import io.cloudbeaver.model.fs.FsBucketNotification;
import io.cloudbeaver.model.fs.FsObjectInfo;
import io.cloudbeaver.model.fs.FsObjectVersion;
import io.cloudbeaver.model.fs.FsMultipartPart;
import io.cloudbeaver.model.fs.FsMultipartUploader;
import io.cloudbeaver.model.fs.FsStackblazeContext;
import io.cloudbeaver.model.fs.FsTransferMonitor;
import io.stackblaze.dbeaver.ext.s3.RustfsConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RustfsNIOFileSystemProvider extends NIOFileSystemProvider implements FsMultipartUploader, FsBucketAdmin {

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

    @NotNull
    @Override
    public String startMultipart(@NotNull Path dest) throws IOException {
        RustfsPath path = requireObjectPath(dest);
        return RustfsS3V4Client.create(connection).createMultipart(path.getBucketName(), path.getObjectKey());
    }

    @NotNull
    @Override
    public String uploadPart(
        @NotNull Path dest,
        @NotNull String uploadId,
        int partNumber,
        @NotNull InputStream data,
        long size
    ) throws IOException {
        RustfsPath path = requireObjectPath(dest);
        return RustfsS3V4Client.create(connection)
            .uploadPart(path.getBucketName(), path.getObjectKey(), uploadId, partNumber, data, size);
    }

    @Override
    public void completeMultipart(
        @NotNull Path dest,
        @NotNull String uploadId,
        @NotNull List<FsMultipartPart> parts
    ) throws IOException {
        RustfsPath path = requireObjectPath(dest);
        RustfsS3V4Client.create(connection).complete(path.getBucketName(), path.getObjectKey(), uploadId, parts);
    }

    @Override
    public void abortMultipart(@NotNull Path dest, @NotNull String uploadId) throws IOException {
        RustfsPath path = requireObjectPath(dest);
        RustfsS3V4Client.create(connection).abort(path.getBucketName(), path.getObjectKey(), uploadId);
    }

    @NotNull
    @Override
    public String getBucketPolicy(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            String policy = getMinioClient().getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
            return CommonUtils.isEmpty(policy) ? "{}" : policy;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such")) {
                return "{}";
            }
            throw new IOException("Failed to read bucket policy: " + e.getMessage(), e);
        }
    }

    @Override
    public void setBucketPolicy(@NotNull Path bucketPath, @NotNull String policy) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            getMinioClient().setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
        } catch (Exception e) {
            throw new IOException("Failed to set bucket policy: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FsBucketNotification getBucketNotification(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            NotificationConfiguration config = getMinioClient().getBucketNotification(
                GetBucketNotificationArgs.builder().bucket(bucket).build()
            );
            java.util.LinkedHashSet<String> events = new java.util.LinkedHashSet<>();
            String arn = null;
            if (config != null && config.queueConfigurationList() != null) {
                for (QueueConfiguration queue : config.queueConfigurationList()) {
                    if (queue == null) {
                        continue;
                    }
                    if (!CommonUtils.isEmpty(queue.queue())) {
                        arn = queue.queue();
                    }
                    if (queue.events() != null) {
                        for (EventType event : queue.events()) {
                            events.add(event.toString());
                        }
                    }
                }
            }
            return new FsBucketNotification(List.copyOf(events), arn);
        } catch (Exception e) {
            throw new IOException("Failed to read bucket notification: " + e.getMessage(), e);
        }
    }

    @Override
    public void setBucketNotification(
        @NotNull Path bucketPath,
        @NotNull List<String> events,
        @Nullable String targetArn
    ) throws IOException {
        String bucket = requireBucketName(bucketPath);
        String arn = CommonUtils.isEmpty(targetArn) ? KUBERO_WEBHOOK_ARN : targetArn;
        try {
            QueueConfiguration queue = new QueueConfiguration();
            queue.setQueue(arn);
            java.util.LinkedList<EventType> eventTypes = new java.util.LinkedList<>();
            for (String event : events) {
                eventTypes.add(EventType.fromString(event));
            }
            queue.setEvents(eventTypes);
            NotificationConfiguration config = new NotificationConfiguration();
            java.util.LinkedList<QueueConfiguration> queues = new java.util.LinkedList<>();
            queues.add(queue);
            config.setQueueConfigurationList(queues);
            getMinioClient().setBucketNotification(
                SetBucketNotificationArgs.builder().bucket(bucket).config(config).build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to set bucket notification: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeBucketNotification(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            getMinioClient().deleteBucketNotification(DeleteBucketNotificationArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new IOException("Failed to remove bucket notification: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public String getBucketVersioning(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            VersioningConfiguration config = getMinioClient().getBucketVersioning(
                GetBucketVersioningArgs.builder().bucket(bucket).build()
            );
            if (config == null || config.status() == null || config.status() == VersioningConfiguration.Status.OFF) {
                return "Off";
            }
            return config.status() == VersioningConfiguration.Status.SUSPENDED ? "Suspended" : "Enabled";
        } catch (Exception e) {
            throw new IOException("Failed to read bucket versioning: " + e.getMessage(), e);
        }
    }

    @Override
    public void setBucketVersioning(@NotNull Path bucketPath, @NotNull String status) throws IOException {
        String bucket = requireBucketName(bucketPath);
        VersioningConfiguration.Status parsed;
        try {
            parsed = VersioningConfiguration.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IOException("Versioning status must be Off, Enabled, or Suspended");
        }
        if (parsed == VersioningConfiguration.Status.OFF) {
            throw new IOException("S3 cannot turn versioning Off after it has been enabled; use Suspended");
        }
        try {
            getMinioClient().setBucketVersioning(
                SetBucketVersioningArgs.builder()
                    .bucket(bucket)
                    .config(new VersioningConfiguration(parsed, null))
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to set bucket versioning: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FsBucketEncryption getBucketEncryption(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            SseConfiguration config = getMinioClient().getBucketEncryption(
                GetBucketEncryptionArgs.builder().bucket(bucket).build()
            );
            SseConfigurationRule rule = config == null ? null : config.rule();
            if (rule == null || rule.sseAlgorithm() == null) {
                return new FsBucketEncryption(null, null);
            }
            return new FsBucketEncryption(
                rule.sseAlgorithm().toString(),
                rule.kmsMasterKeyId()
            );
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return new FsBucketEncryption(null, null);
            }
            throw new IOException("Failed to read bucket encryption: " + e.getMessage(), e);
        }
    }

    @Override
    public void setBucketEncryption(
        @NotNull Path bucketPath,
        @NotNull String algorithm,
        @Nullable String kmsKeyId
    ) throws IOException {
        String bucket = requireBucketName(bucketPath);
        SseConfiguration config;
        if (!CommonUtils.isEmpty(kmsKeyId) || "aws:kms".equalsIgnoreCase(algorithm)) {
            if (CommonUtils.isEmpty(kmsKeyId)) {
                throw new IOException("KMS encryption requires a key id");
            }
            config = SseConfiguration.newConfigWithSseKmsRule(kmsKeyId);
        } else {
            config = SseConfiguration.newConfigWithSseS3Rule();
        }
        try {
            getMinioClient().setBucketEncryption(
                SetBucketEncryptionArgs.builder().bucket(bucket).config(config).build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to set bucket encryption: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeBucketEncryption(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            getMinioClient().deleteBucketEncryption(DeleteBucketEncryptionArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new IOException("Failed to remove bucket encryption: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public Map<String, String> getBucketTags(@NotNull Path bucketPath) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            Tags tags = getMinioClient().getBucketTags(GetBucketTagsArgs.builder().bucket(bucket).build());
            return tags == null || tags.get() == null ? Map.of() : new LinkedHashMap<>(tags.get());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return Map.of();
            }
            throw new IOException("Failed to read bucket tags: " + e.getMessage(), e);
        }
    }

    @Override
    public void setBucketTags(@NotNull Path bucketPath, @NotNull Map<String, String> tags) throws IOException {
        String bucket = requireBucketName(bucketPath);
        try {
            getMinioClient().setBucketTags(
                SetBucketTagsArgs.builder().bucket(bucket).tags(Tags.newBucketTags(tags)).build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to set bucket tags: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public Map<String, String> getObjectTags(@NotNull Path objectPath) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            Tags tags = getMinioClient().getObjectTags(
                GetObjectTagsArgs.builder().bucket(path.getBucketName()).object(path.getObjectKey()).build()
            );
            return tags == null || tags.get() == null ? Map.of() : new LinkedHashMap<>(tags.get());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return Map.of();
            }
            throw new IOException("Failed to read object tags: " + e.getMessage(), e);
        }
    }

    @Override
    public void setObjectTags(@NotNull Path objectPath, @NotNull Map<String, String> tags) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            getMinioClient().setObjectTags(
                SetObjectTagsArgs.builder()
                    .bucket(path.getBucketName())
                    .object(path.getObjectKey())
                    .tags(Tags.newObjectTags(tags))
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to set object tags: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteObjectTags(@NotNull Path objectPath) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            getMinioClient().deleteObjectTags(
                DeleteObjectTagsArgs.builder().bucket(path.getBucketName()).object(path.getObjectKey()).build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to delete object tags: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FsObjectInfo getObjectInfo(@NotNull Path objectPath, @Nullable String versionId) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            var builder = StatObjectArgs.builder().bucket(path.getBucketName()).object(path.getObjectKey());
            if (!CommonUtils.isEmpty(versionId)) {
                builder.versionId(versionId);
            }
            StatObjectResponse stat = getMinioClient().statObject(builder.build());
            String encryption = null;
            String storageClass = null;
            if (stat.headers() != null) {
                encryption = stat.headers().get("X-Amz-Server-Side-Encryption");
                storageClass = stat.headers().get("X-Amz-Storage-Class");
            }
            return new FsObjectInfo(
                stat.size(),
                stat.etag(),
                stat.lastModified() == null ? null : stat.lastModified().toString(),
                CommonUtils.isEmpty(storageClass) ? "STANDARD" : storageClass,
                stat.versionId(),
                encryption
            );
        } catch (Exception e) {
            throw new IOException("Failed to read object info: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public List<FsObjectVersion> listObjectVersions(@NotNull Path objectPath) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        String key = path.getObjectKey();
        List<FsObjectVersion> versions = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = getMinioClient().listObjects(
                ListObjectsArgs.builder()
                    .bucket(path.getBucketName())
                    .prefix(key)
                    .includeVersions(true)
                    .recursive(false)
                    .build()
            );
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item == null || !key.equals(item.objectName())) {
                    continue;
                }
                versions.add(new FsObjectVersion(
                    item.versionId(),
                    item.isLatest(),
                    item.isDeleteMarker(),
                    item.size(),
                    item.etag(),
                    item.lastModified() == null ? null : item.lastModified().toString(),
                    CommonUtils.isEmpty(item.storageClass()) ? "STANDARD" : item.storageClass()
                ));
            }
            return versions;
        } catch (Exception e) {
            throw new IOException("Failed to list object versions: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteObjectVersion(@NotNull Path objectPath, @NotNull String versionId) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            getMinioClient().removeObject(
                RemoveObjectArgs.builder()
                    .bucket(path.getBucketName())
                    .object(path.getObjectKey())
                    .versionId(versionId)
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to delete object version: " + e.getMessage(), e);
        }
    }

    @Override
    public void restoreObjectVersion(@NotNull Path objectPath, @NotNull String versionId) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            getMinioClient().copyObject(
                CopyObjectArgs.builder()
                    .bucket(path.getBucketName())
                    .object(path.getObjectKey())
                    .metadataDirective(Directive.REPLACE)
                    .source(CopySource.builder()
                        .bucket(path.getBucketName())
                        .object(path.getObjectKey())
                        .versionId(versionId)
                        .build())
                    .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to restore object version: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public InputStream openObject(@NotNull Path objectPath, @Nullable String versionId) throws IOException {
        RustfsPath path = requireObjectPath(objectPath);
        try {
            var builder = GetObjectArgs.builder().bucket(path.getBucketName()).object(path.getObjectKey());
            if (!CommonUtils.isEmpty(versionId)) {
                builder.versionId(versionId);
            }
            return getMinioClient().getObject(builder.build());
        } catch (Exception e) {
            throw new IOException("Failed to open object: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FsStackblazeContext stackblazeContext() {
        try {
            DBPConnectionConfiguration cfg = connection.getDataSourceContainer().getActualConnectionConfiguration();
            String pipeline = firstNonEmpty(
                cfg.getProviderProperty("stackblazePipeline"),
                cfg.getProperty("stackblazePipeline")
            );
            String phase = firstNonEmpty(
                cfg.getProviderProperty("stackblazePhase"),
                cfg.getProperty("stackblazePhase")
            );
            String instance = firstNonEmpty(
                cfg.getProviderProperty("stackblazeInstance"),
                cfg.getProperty("stackblazeInstance")
            );
            if (!CommonUtils.isEmpty(pipeline) && !CommonUtils.isEmpty(phase) && !CommonUtils.isEmpty(instance)) {
                return new FsStackblazeContext(pipeline, phase, instance);
            }
        } catch (Exception ignored) {
            // Fall back to the connection display name.
        }
        String name = connection.getName();
        if (CommonUtils.isEmpty(name)) {
            name = connection.getDataSourceContainer().getName();
        }
        if (CommonUtils.isEmpty(name)) {
            return new FsStackblazeContext(null, null, null);
        }
        String cleaned = name.replace(" · SQL (DuckDB)", "");
        String[] parts = cleaned.split("/");
        if (parts.length < 3) {
            return new FsStackblazeContext(null, null, null);
        }
        return new FsStackblazeContext(parts[0], parts[1], parts[2]);
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!CommonUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    @NotNull
    private String requireBucketName(@NotNull Path path) throws IOException {
        RustfsPath rustfs = (RustfsPath) path;
        String bucket = rustfs.getBucketName();
        if (CommonUtils.isEmpty(bucket)) {
            throw new IOException("Not a bucket path");
        }
        return bucket;
    }

    @NotNull
    private RustfsPath requireObjectPath(@NotNull Path dest) throws IOException {
        RustfsPath path = (RustfsPath) dest;
        if (path.isConnectionRoot() || path.isBucketPath()
            || CommonUtils.isEmpty(path.getBucketName())
            || CommonUtils.isEmpty(path.getObjectKey())
        ) {
            throw new IOException("Not an S3 object");
        }
        return path;
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
                FsTransferMonitor.checkCancelled();
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
        FsTransferMonitor.checkCancelled();
        try {
            if (isFolderObject(src)) {
                copyPrefix(src, dst);
                return;
            }
            if (FsTransferMonitor.isResume() && objectExists(dst.getBucketName(), dst.getObjectKey())) {
                FsTransferMonitor.status("Skipped existing " + dst.getObjectKey());
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
        if (hasPrefixObjects(path.getBucketName(), key.endsWith("/") ? key : key + "/")) {
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
        Set<String> existing = FsTransferMonitor.isResume()
            ? listRelativeKeys(dst.getBucketName(), dstPrefix)
            : Set.of();
        boolean copiedMarker = existing.contains("")
            || copyObjectIfExists(src.getBucketName(), srcPrefix, dst.getBucketName(), dstPrefix);
        int copied = copiedMarker && !existing.contains("") ? 1 : 0;
        int skipped = existing.contains("") ? 1 : 0;
        int failed = 0;
        Iterable<Result<Item>> results = getMinioClient().listObjects(
            ListObjectsArgs.builder().bucket(src.getBucketName()).prefix(srcPrefix).recursive(true).build()
        );
        for (Result<Item> result : results) {
            FsTransferMonitor.checkCancelled();
            Item item = result.get();
            if (item.isDir() || CommonUtils.isEmpty(item.objectName())) {
                continue;
            }
            String key = item.objectName();
            if (key.equals(srcPrefix)) {
                continue;
            }
            String relative = key.startsWith(srcPrefix) ? key.substring(srcPrefix.length()) : key;
            if (existing.contains(relative)) {
                skipped++;
                if (skipped % 100 == 0) {
                    FsTransferMonitor.status("Skipped " + skipped + " existing objects");
                }
                continue;
            }
            try {
                copyObject(src.getBucketName(), key, dst.getBucketName(), dstPrefix + relative);
                copied++;
            } catch (Exception e) {
                failed++;
            }
            if (copied % 25 == 0) {
                FsTransferMonitor.status("Copied " + copied + (skipped > 0 ? ", skipped " + skipped : "") + " objects");
            }
        }
        if (copied == 0 && !copiedMarker) {
            putObject(dst.getBucketName(), dstPrefix, new byte[0]);
        }
        if (failed > 0) {
            throw new IOException("Copied " + copied + " objects, " + failed + " failed");
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

    private boolean objectExists(String bucket, String key) {
        try {
            getMinioClient().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NotNull
    private Set<String> listRelativeKeys(String bucket, String prefix) throws Exception {
        Set<String> keys = new HashSet<>();
        Iterable<Result<Item>> results = getMinioClient().listObjects(
            ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build()
        );
        for (Result<Item> result : results) {
            FsTransferMonitor.checkCancelled();
            Item item = result.get();
            if (item.isDir() || CommonUtils.isEmpty(item.objectName())) {
                continue;
            }
            String key = item.objectName();
            if (key.equals(prefix)) {
                keys.add("");
            } else if (key.startsWith(prefix)) {
                keys.add(key.substring(prefix.length()));
            }
        }
        return keys;
    }

    private boolean hasPrefixObjects(String bucket, String prefix) throws Exception {
        Iterable<Result<Item>> results = getMinioClient().listObjects(
            ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).maxKeys(1).build()
        );
        return results.iterator().hasNext();
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
