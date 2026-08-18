/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
package io.cloudbeaver.model.fs;

import org.jkiss.code.Nullable;

public record FsStackblazeContext(
    @Nullable String pipeline,
    @Nullable String phase,
    @Nullable String instance
) {
}
