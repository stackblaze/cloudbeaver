/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
package io.cloudbeaver.service.fs.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.meta.Property;

public class FSTag {
    @NotNull
    private final String key;
    @NotNull
    private final String value;

    public FSTag(@NotNull String key, @NotNull String value) {
        this.key = key;
        this.value = value;
    }

    @Property
    @NotNull
    public String getKey() {
        return key;
    }

    @Property
    @NotNull
    public String getValue() {
        return value;
    }
}
