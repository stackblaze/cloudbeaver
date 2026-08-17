package io.stackblaze.dbeaver.ext.s3.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSObject;

/**
 * An S3 bucket — a navigator leaf. Object browsing goes through the
 * RustFS virtual file system, not the database navigator.
 */
public class RustfsBucket implements DBSObject {

    private final RustfsDataSource dataSource;
    private final String name;
    private final String creationDate;

    public RustfsBucket(
        @NotNull RustfsDataSource dataSource,
        @NotNull String name,
        @Nullable String creationDate
    ) {
        this.dataSource = dataSource;
        this.name = name;
        this.creationDate = creationDate;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public String getCreationDate() {
        return creationDate;
    }

    @Nullable
    @Override
    public String getDescription() {
        return "S3 bucket " + name;
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
    public RustfsDataSource getDataSource() {
        return dataSource;
    }
}
