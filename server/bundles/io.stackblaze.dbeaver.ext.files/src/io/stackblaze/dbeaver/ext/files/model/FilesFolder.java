package io.stackblaze.dbeaver.ext.files.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A directory on the leftover volume. */
public class FilesFolder implements DBSObject, DBSObjectContainer {

    private final FilesDataSource dataSource;
    private final DBSObject parent;
    private final String path;
    private final String name;
    private List<DBSObject> items;

    public FilesFolder(
        @NotNull FilesDataSource dataSource,
        @Nullable DBSObject parent,
        @NotNull String path,
        @NotNull String name
    ) {
        this.dataSource = dataSource;
        this.parent = parent;
        this.path = path;
        this.name = name;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public String getPath() {
        return path;
    }

    @Nullable
    @Override
    public String getDescription() {
        return "Folder";
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public FilesDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    public synchronized List<DBSObject> getItems(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (items != null) {
            return items;
        }
        List<DBSObject> found = new ArrayList<>();
        monitor.subTask("List " + path);
        for (FilesClient.Entry e : dataSource.client().list(path)) {
            String childPath = path.endsWith("/") ? path + e.name() : path + "/" + e.name();
            if (path.equals("/")) {
                childPath = "/" + e.name();
            }
            if (e.directory()) {
                found.add(new FilesFolder(dataSource, this, childPath, e.name()));
            } else {
                found.add(new FilesEntry(dataSource, this, childPath, e.name(), e.size()));
            }
        }
        items = found;
        return items;
    }

    @Nullable
    public List<DBSObject> getItems() {
        if (items == null && dataSource.isConnected()) {
            try {
                return getItems(new VoidProgressMonitor());
            } catch (DBException e) {
                return null;
            }
        }
        return items;
    }

    @Nullable
    @Override
    public Collection<? extends DBSObject> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getItems(monitor);
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        for (DBSObject o : getItems(monitor)) {
            if (o.getName().equals(childName)) {
                return o;
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return FilesEntry.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        items = null;
        getItems(monitor);
    }
}
