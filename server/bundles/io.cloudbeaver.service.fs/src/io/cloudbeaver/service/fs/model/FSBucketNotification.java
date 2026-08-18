/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
package io.cloudbeaver.service.fs.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.meta.Property;

import java.util.List;

public class FSBucketNotification {
    @NotNull
    private final List<String> events;
    @Nullable
    private final String targetArn;

    public FSBucketNotification(@NotNull List<String> events, @Nullable String targetArn) {
        this.events = events;
        this.targetArn = targetArn;
    }

    @Property
    @NotNull
    public List<String> getEvents() {
        return events;
    }

    @Property
    @Nullable
    public String getTargetArn() {
        return targetArn;
    }
}
