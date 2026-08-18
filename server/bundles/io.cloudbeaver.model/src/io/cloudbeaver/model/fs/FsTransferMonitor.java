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
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.io.InterruptedIOException;

/**
 * Lets NIO copy/move report progress and honour cancel without changing the
 * {@link java.nio.file.Files} signature. The async FS transfer task binds the
 * monitor to the worker thread for the duration of the job.
 */
public final class FsTransferMonitor {

    private static final ThreadLocal<DBRProgressMonitor> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> RESUME = new ThreadLocal<>();

    private FsTransferMonitor() {
    }

    public static void set(@Nullable DBRProgressMonitor monitor) {
        set(monitor, false);
    }

    public static void set(@Nullable DBRProgressMonitor monitor, boolean resume) {
        if (monitor == null) {
            CURRENT.remove();
            RESUME.remove();
        } else {
            CURRENT.set(monitor);
            RESUME.set(resume);
        }
    }

    public static void clear() {
        CURRENT.remove();
        RESUME.remove();
    }

    public static boolean isResume() {
        return Boolean.TRUE.equals(RESUME.get());
    }

    public static void checkCancelled() throws InterruptedIOException {
        DBRProgressMonitor monitor = CURRENT.get();
        if ((monitor != null && monitor.isCanceled()) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Transfer cancelled");
        }
    }

    public static void status(@NotNull String status) {
        DBRProgressMonitor monitor = CURRENT.get();
        if (monitor != null) {
            monitor.subTask(status);
        }
    }
}
