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
import java.nio.file.spi.FileSystemProvider;
import java.nio.file.Path;
import java.util.List;

/**
 * S3-style multipart upload. Implemented by the RustFS NIO provider so the
 * generic fs servlet can start / part / complete / abort without depending on MinIO.
 */
public interface FsMultipartUploader {

    long PART_SIZE = 8L * 1024 * 1024;

    @NotNull
    String startMultipart(@NotNull Path dest) throws IOException;

    @NotNull
    String uploadPart(
        @NotNull Path dest,
        @NotNull String uploadId,
        int partNumber,
        @NotNull InputStream data,
        long size
    ) throws IOException;

    void completeMultipart(
        @NotNull Path dest,
        @NotNull String uploadId,
        @NotNull List<FsMultipartPart> parts
    ) throws IOException;

    void abortMultipart(@NotNull Path dest, @NotNull String uploadId) throws IOException;

    @Nullable
    static FsMultipartUploader of(@NotNull Path path) {
        FileSystemProvider provider = path.getFileSystem().provider();
        return provider instanceof FsMultipartUploader uploader ? uploader : null;
    }
}
