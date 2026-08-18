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

public class FSObjectVersion {
    @Nullable
    private final String versionId;
    private final boolean latest;
    private final boolean deleteMarker;
    private final long size;
    @Nullable
    private final String etag;
    @Nullable
    private final String lastModified;
    @Nullable
    private final String storageClass;

    public FSObjectVersion(
        @Nullable String versionId,
        boolean latest,
        boolean deleteMarker,
        long size,
        @Nullable String etag,
        @Nullable String lastModified,
        @Nullable String storageClass
    ) {
        this.versionId = versionId;
        this.latest = latest;
        this.deleteMarker = deleteMarker;
        this.size = size;
        this.etag = etag;
        this.lastModified = lastModified;
        this.storageClass = storageClass;
    }

    @Property
    @Nullable
    public String getVersionId() {
        return versionId;
    }

    @Property
    public boolean isLatest() {
        return latest;
    }

    @Property
    public boolean isDeleteMarker() {
        return deleteMarker;
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
}
