/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
package io.cloudbeaver.model.fs;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Bucket policy and S3 notification admin, implemented by the RustFS NIO provider. */
public interface FsBucketAdmin {

    String KUBERO_WEBHOOK_ARN = "arn:rustfs:sqs::kubero:webhook";

    @NotNull
    String getBucketPolicy(@NotNull Path bucketPath) throws IOException;

    void setBucketPolicy(@NotNull Path bucketPath, @NotNull String policy) throws IOException;

    @NotNull
    FsBucketNotification getBucketNotification(@NotNull Path bucketPath) throws IOException;

    void setBucketNotification(
        @NotNull Path bucketPath,
        @NotNull List<String> events,
        @Nullable String targetArn
    ) throws IOException;

    void removeBucketNotification(@NotNull Path bucketPath) throws IOException;

    @NotNull
    String getBucketVersioning(@NotNull Path bucketPath) throws IOException;

    void setBucketVersioning(@NotNull Path bucketPath, @NotNull String status) throws IOException;

    @NotNull
    FsBucketEncryption getBucketEncryption(@NotNull Path bucketPath) throws IOException;

    void setBucketEncryption(
        @NotNull Path bucketPath,
        @NotNull String algorithm,
        @Nullable String kmsKeyId
    ) throws IOException;

    void removeBucketEncryption(@NotNull Path bucketPath) throws IOException;

    @NotNull
    Map<String, String> getBucketTags(@NotNull Path bucketPath) throws IOException;

    void setBucketTags(@NotNull Path bucketPath, @NotNull Map<String, String> tags) throws IOException;

    @NotNull
    Map<String, String> getObjectTags(@NotNull Path objectPath) throws IOException;

    void setObjectTags(@NotNull Path objectPath, @NotNull Map<String, String> tags) throws IOException;

    void deleteObjectTags(@NotNull Path objectPath) throws IOException;

    @NotNull
    FsObjectInfo getObjectInfo(@NotNull Path objectPath, @Nullable String versionId) throws IOException;

    @NotNull
    List<FsObjectVersion> listObjectVersions(@NotNull Path objectPath) throws IOException;

    void deleteObjectVersion(@NotNull Path objectPath, @NotNull String versionId) throws IOException;

    void restoreObjectVersion(@NotNull Path objectPath, @NotNull String versionId) throws IOException;

    @NotNull
    InputStream openObject(@NotNull Path objectPath, @Nullable String versionId) throws IOException;

    @NotNull
    FsStackblazeContext stackblazeContext();

    @Nullable
    static FsBucketAdmin of(@NotNull Path path) {
        FileSystemProvider provider = path.getFileSystem().provider();
        return provider instanceof FsBucketAdmin admin ? admin : null;
    }
}
