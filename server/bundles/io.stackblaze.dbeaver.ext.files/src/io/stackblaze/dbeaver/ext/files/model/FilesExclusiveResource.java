package io.stackblaze.dbeaver.ext.files.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPExclusiveResource;

import java.util.HashSet;
import java.util.Set;

public class FilesExclusiveResource implements DBPExclusiveResource {

    private final Object mutex = new Object();
    private final Set<String> tasks = new HashSet<>();
    private boolean locked;

    @Override
    public Object acquireExclusiveLock() {
        synchronized (mutex) {
            while (locked) {
                try {
                    mutex.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            locked = true;
            return mutex;
        }
    }

    @Override
    public void releaseExclusiveLock(@NotNull Object lock) {
        synchronized (mutex) {
            locked = false;
            mutex.notifyAll();
        }
    }

    @NotNull
    @Override
    public Object acquireTaskLock(@NotNull String taskName, boolean checkDup) {
        synchronized (mutex) {
            if (checkDup && tasks.contains(taskName)) {
                return TASK_PROCESED;
            }
            tasks.add(taskName);
            return taskName;
        }
    }

    @Override
    public void releaseTaskLock(@NotNull String taskName, @NotNull Object lock) {
        synchronized (mutex) {
            tasks.remove(taskName);
        }
    }
}
