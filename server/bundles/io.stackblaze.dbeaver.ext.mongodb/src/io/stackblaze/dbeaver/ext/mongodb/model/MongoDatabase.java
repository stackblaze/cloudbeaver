package io.stackblaze.dbeaver.ext.mongodb.model;

import com.mongodb.client.MongoClient;
import io.stackblaze.dbeaver.ext.mongodb.MongoConstants;
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
import java.util.Collections;
import java.util.List;

/**
 * A MongoDB database. Children are collections (lazy, capped).
 */
public class MongoDatabase implements DBSObject, DBSObjectContainer {

    private final MongoDataSource dataSource;
    private final String name;
    private List<MongoCollection> collections;

    public MongoDatabase(@NotNull MongoDataSource dataSource, @NotNull String name) {
        this.dataSource = dataSource;
        this.name = name;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 2)
    public String getDescription() {
        return "MongoDB database " + name;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public MongoDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    public synchronized List<MongoCollection> getCollections(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (collections != null) {
            return collections;
        }
        monitor.subTask("List collections in " + name);
        List<MongoCollection> found = new ArrayList<>();
        try {
            MongoClient client = dataSource.getClient();
            List<String> names = new ArrayList<>();
            for (String collName : client.getDatabase(name).listCollectionNames()) {
                names.add(collName);
                if (names.size() >= MongoConstants.MAX_COLLECTIONS) {
                    break;
                }
            }
            Collections.sort(names);
            for (String collName : names) {
                found.add(new MongoCollection(this, collName));
            }
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Failed to list collections in '" + name + "': " + e.getMessage(), e);
        }
        collections = found;
        return collections;
    }

    /**
     * Property accessor used by the navigator tree (`property="collections"`).
     * Returns null when not yet loaded so the tree treats children as lazy
     * (an empty list would make the node look childless forever).
     */
    @Nullable
    public List<MongoCollection> getCollections() {
        if (collections == null && dataSource.isConnected()) {
            try {
                return getCollections(new VoidProgressMonitor());
            } catch (DBException e) {
                return null;
            }
        }
        return collections;
    }

    @Nullable
    @Override
    public Collection<? extends DBSObject> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getCollections(monitor);
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        for (MongoCollection collection : getCollections(monitor)) {
            if (collection.getName().equals(childName)) {
                return collection;
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return MongoCollection.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        collections = null;
        getCollections(monitor);
    }
}
