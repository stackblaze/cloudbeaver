/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
package io.cloudbeaver.service.fs.model;

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.meta.Property;

public class FSObjectInfo {
    private final long size;
    @Nullable
    private final String etag;
    @Nullable
    private final String lastModified;
    @Nullable
    private final String storageClass;
    @Nullable
    private final String versionId;
    @Nullable
    private final String encryption;

    public FSObjectInfo(
        long size,
        @Nullable String etag,
        @Nullable String lastModified,
        @Nullable String storageClass,
        @Nullable String versionId,
        @Nullable String encryption
    ) {
        this.size = size;
        this.etag = etag;
        this.lastModified = lastModified;
        this.storageClass = storageClass;
        this.versionId = versionId;
        this.encryption = encryption;
    }

    @Property
    public long getSize() {
        return size;
    }

    @Property
    @Nullable
    public String getEtag() {
        return etag;
    }

    @Property
    @Nullable
    public String getLastModified() {
        return lastModified;
    }

    @Property
    @Nullable
    public String getStorageClass() {
        return storageClass;
    }

    @Property
    @Nullable
    public String getVersionId() {
        return versionId;
    }

    @Property
    @Nullable
    public String getEncryption() {
        return encryption;
    }
}
