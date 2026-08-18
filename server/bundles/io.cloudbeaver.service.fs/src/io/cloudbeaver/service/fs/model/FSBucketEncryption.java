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

public class FSBucketEncryption {
    @Nullable
    private final String algorithm;
    @Nullable
    private final String kmsKeyId;

    public FSBucketEncryption(@Nullable String algorithm, @Nullable String kmsKeyId) {
        this.algorithm = algorithm;
        this.kmsKeyId = kmsKeyId;
    }

    @Property
    @Nullable
    public String getAlgorithm() {
        return algorithm;
    }

    @Property
    @Nullable
    public String getKmsKeyId() {
        return kmsKeyId;
    }
}
