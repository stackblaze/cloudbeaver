package io.stackblaze.dbeaver.ext.s3;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.navigator.DBNModelExtender;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNRoot;
import org.jkiss.dbeaver.model.navigator.fs.DBNFileSystems;

/**
 * Attaches the "Remote file systems" node to the navigator root so the
 * Cloud Storage panel (fsListFileSystems GraphQL) can find it. CE web
 * sessions have no other extender creating DBNFileSystems; without this
 * the fs service throws "File systems not found in navigator".
 */
public class RustfsNavigatorModelExtender implements DBNModelExtender {

    @Override
    public DBNNode createNode(@NotNull DBNNode parentNode) {
        if (parentNode instanceof DBNRoot root) {
            return new DBNFileSystems(root);
        }
        return null;
    }
}
