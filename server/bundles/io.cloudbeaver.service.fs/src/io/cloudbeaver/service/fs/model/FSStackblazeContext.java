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

public class FSStackblazeContext {
    @Nullable
    private final String pipeline;
    @Nullable
    private final String phase;
    @Nullable
    private final String instance;

    public FSStackblazeContext(@Nullable String pipeline, @Nullable String phase, @Nullable String instance) {
        this.pipeline = pipeline;
        this.phase = phase;
        this.instance = instance;
    }

    @Property
    @Nullable
    public String getPipeline() {
        return pipeline;
    }

    @Property
    @Nullable
    public String getPhase() {
        return phase;
    }

    @Property
    @Nullable
    public String getInstance() {
        return instance;
    }
}
