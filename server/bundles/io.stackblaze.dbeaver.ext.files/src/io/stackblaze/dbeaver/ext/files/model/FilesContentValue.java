package io.stackblaze.dbeaver.ext.files.model;

import io.stackblaze.dbeaver.ext.files.FilesConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.data.storage.BytesContentStorage;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * A file's real content, fetched lazily on first open. Gives the grid's LOB
 * panel (view/hex/image sub-viewers, Save-to-file) real byte-exact data,
 * separate from the small truncated text preview shown inline in the grid.
 * Read-only: the engine never writes back to the volume.
 */
public class FilesContentValue implements DBDContent {

    private final FilesDataSource dataSource;
    private final String path;
    private final long declaredSize;
    private DBDContentStorage storage;

    public FilesContentValue(@NotNull FilesDataSource dataSource, @NotNull String path, long declaredSize) {
        this.dataSource = dataSource;
        this.path = path;
        this.declaredSize = declaredSize;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public long getContentLength() {
        return declaredSize;
    }

    @NotNull
    @Override
    public String getContentType() {
        return "application/octet-stream";
    }

    @Nullable
    @Override
    public String getDisplayString(@NotNull DBDDisplayFormat format) {
        return "[FILE " + declaredSize + " bytes]";
    }

    @Nullable
    @Override
    public DBDContentStorage getContents(@NotNull DBRProgressMonitor monitor) throws DBCException {
        if (storage == null) {
            if (declaredSize > FilesConstants.MAX_DOWNLOAD_BYTES) {
                throw new DBCException(
                    "File is " + declaredSize + " bytes — over the "
                        + FilesConstants.MAX_DOWNLOAD_BYTES + " byte view/download limit");
            }
            try {
                byte[] data = dataSource.client().catBytes(path, declaredSize);
                storage = new BytesContentStorage(data, "UTF-8");
            } catch (DBException e) {
                throw new DBCException("Failed to fetch file content: " + e.getMessage(), e);
            }
        }
        return storage;
    }

    @Override
    public boolean updateContents(
        @NotNull DBRProgressMonitor monitor,
        @Nullable DBDContentStorage newStorage
    ) {
        // Read-only engine: leftover-volume columns are never updatable
        // (LocalResultSetColumn.isReadOnly() is always true), so the UI has
        // no path to reach this — kept as an explicit no-op, not a stub bug.
        return false;
    }

    @Override
    public void resetContents() {
        if (storage != null) {
            storage.release();
            storage = null;
        }
    }

    @Nullable
    @Override
    public Object getRawValue() {
        return this;
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void release() {
        if (storage != null) {
            storage.release();
            storage = null;
        }
    }
}
