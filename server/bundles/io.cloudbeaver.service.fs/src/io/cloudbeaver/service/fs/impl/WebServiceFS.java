/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudbeaver.service.fs.impl;

import io.cloudbeaver.DBWConstants;
import io.cloudbeaver.DBWebException;
import io.cloudbeaver.model.WebAsyncTaskInfo;
import io.cloudbeaver.model.fs.FsBucketAdmin;
import io.cloudbeaver.model.fs.FsBucketNotification;
import io.cloudbeaver.model.fs.FsStackblazeContext;
import io.cloudbeaver.model.fs.FsTransferMonitor;
import io.cloudbeaver.model.fs.WebFSUtils;
import io.cloudbeaver.model.session.WebAsyncTaskProcessor;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.service.fs.DBWServiceFS;
import io.cloudbeaver.model.fs.FsBucketEncryption;
import io.cloudbeaver.model.fs.FsObjectInfo;
import io.cloudbeaver.model.fs.FsObjectVersion;
import io.cloudbeaver.service.fs.model.FSBucketEncryption;
import io.cloudbeaver.service.fs.model.FSBucketNotification;
import io.cloudbeaver.service.fs.model.FSFile;
import io.cloudbeaver.service.fs.model.FSFileSystem;
import io.cloudbeaver.service.fs.model.FSObjectInfo;
import io.cloudbeaver.service.fs.model.FSObjectVersion;
import io.cloudbeaver.service.fs.model.FSStackblazeContext;
import io.cloudbeaver.service.fs.model.FSTag;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.navigator.fs.DBNFileSystem;
import org.jkiss.dbeaver.model.navigator.fs.DBNFileSystemRoot;
import org.jkiss.dbeaver.model.navigator.fs.DBNFileSystems;
import org.jkiss.dbeaver.model.navigator.fs.DBNPathBase;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.registry.fs.FileSystemProviderRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web file system implementation
 */
public class WebServiceFS implements DBWServiceFS {

    private static final Pattern FORBIDDEN_FILENAME_PATTERN = Pattern.compile("[%#:;№$]");

    /**
     * Resolve the navigator node for a just-created (or just-moved) child.
     * <p>
     * DBNPathBase caches children lazily: both addChildResource() and getChild()
     * return without doing anything while that cache is null, which it is for any
     * folder the user has not expanded. Creating a folder inside a collapsed
     * folder therefore produced an FSFile wrapping a null node, and the failure
     * only surfaced when GraphQL fetched its properties — as an NPE on
     * DBNPathBase.getNodeDisplayName() with nothing pointing at the real cause.
     * Read the children (which also picks up the new entry from storage) before
     * giving up, and fail with something legible if it still is not there.
     */
    @NotNull
    private static DBNPathBase resolveChildNode(
        @NotNull WebSession webSession,
        @NotNull DBNPathBase parentNode,
        @NotNull Path childPath
    ) throws DBException {
        DBNPathBase child = parentNode.getChild(childPath);
        if (child == null) {
            parentNode.getChildren(webSession.getProgressMonitor());
            child = parentNode.getChild(childPath);
        }
        if (child == null) {
            throw new DBException(
                MessageFormat.format("Node ''{0}'' not found after the operation", childPath));
        }
        return child;
    }

    @NotNull
    @Override
    public FSFileSystem[] getAvailableFileSystems(@NotNull WebSession webSession, @NotNull String projectId) throws DBWebException {
        try {
            DBNFileSystems dbnFileSystems = webSession.getNavigatorModelOrThrow().getRoot().getExtraNode(DBNFileSystems.class);
            if (dbnFileSystems == null) {
                throw new DBWebException("File systems not found in navigator");
            }
            var fsRegistry = FileSystemProviderRegistry.getInstance();
            DBNFileSystem[] children = dbnFileSystems.getChildren(webSession.getProgressMonitor());
            if (children == null) {
                return new FSFileSystem[0];
            }
            return Arrays.stream(children)
                .map(fs -> new FSFileSystem(
                    WebFSUtils.makeUniqueFsId(fs.getFileSystem()),
                        fs.getNodeUri(),
                        fsRegistry.getProvider(fs.getFileSystem().getProviderId()).getRequiredAuth()
                    )
                )
                .toArray(FSFileSystem[]::new);
        } catch (Exception e) {
            throw new DBWebException("Failed to load file systems: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSFileSystem getFileSystem(
        @NotNull WebSession webSession,
        @NotNull String projectId,
        @NotNull String nodePath
    ) throws DBWebException {
        try {
            var node = webSession.getNavigatorModelOrThrow().getNodeByPath(webSession.getProgressMonitor(), nodePath);
            if (!(node instanceof DBNFileSystem fs)) {
                throw new DBException(MessageFormat.format("Node ''{0}'' is not File System", nodePath));
            }
            var fsRegistry = FileSystemProviderRegistry.getInstance();
            return new FSFileSystem(
                WebFSUtils.makeUniqueFsId(fs.getFileSystem()),
                fs.getNodeUri(),
                fsRegistry.getProvider(fs.getFileSystem().getProviderId()).getRequiredAuth()
            );
        } catch (Exception e) {
            throw new DBWebException("Failed to get file system: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSFile getFile(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        try {
            DBNPathBase node = WebFSUtils.getNodeByPath(webSession, nodePath);
            return new FSFile(node);
        } catch (Exception e) {
            throw new DBWebException("Failed to found file: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSFile[] getFiles(@NotNull WebSession webSession, @NotNull String parentPath)
        throws DBWebException {
        try {
            // The panel's first navigation target is the file system node itself
            // (fsListFileSystems returns its URI). That node is not a DBNPathBase —
            // list its roots (S3 buckets) as folders.
            var node = webSession.getNavigatorModelOrThrow()
                .getNodeByPath(webSession.getProgressMonitor(), parentPath);
            if (node instanceof DBNFileSystem fsNode) {
                DBNFileSystemRoot[] roots = fsNode.getChildren(webSession.getProgressMonitor());
                if (roots == null) {
                    return new FSFile[0];
                }
                return Arrays.stream(roots)
                    .map(FSFile::new)
                    .toArray(FSFile[]::new);
            }
            DBNPathBase folderPath = WebFSUtils.getNodeByPath(webSession, parentPath);
            var children = folderPath.getChildren(webSession.getProgressMonitor());
            if (children == null) {
                return new FSFile[0];
            }
            return Arrays.stream(children)
                .filter(c -> c instanceof DBNPathBase)
                .map(c -> (DBNPathBase) c)
                .map(FSFile::new)
                .toArray(FSFile[]::new);
        } catch (Exception e) {
            throw new DBWebException("Failed to list files: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public String readFileContent(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        try {
            Path filePath = WebFSUtils.getPathFromNode(webSession, nodePath);
            var data = Files.readAllBytes(filePath);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new DBWebException("Failed to read file content: " + e.getMessage(), e);
        }
    }

    @Override
    public FSFile writeFileContent(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String data,
        boolean forceOverwrite
    )
        throws DBWebException {
        validateEditPermissions(webSession);
        try {
            DBNPathBase node = WebFSUtils.getNodeByPath(webSession, nodePath);
            Path filePath = node.getPath();
            if (!forceOverwrite) {
                throw new DBException("Cannot overwrite exist file");
            }
            Files.writeString(filePath, data);
            return new FSFile(node);
        } catch (Exception e) {
            throw new DBWebException("Failed to write file content: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSFile createFile(
        @NotNull WebSession webSession,
        @NotNull String parentPath,
        @NotNull String fileName
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            DBNPathBase parentNode = WebFSUtils.getNodeByPath(webSession, parentPath);
            if (!Files.isDirectory(parentNode.getPath())) {
                throw new DBException(MessageFormat.format("Node ''{0}'' is not a directory", parentPath));
            }
            Path filePath = parentNode.getPath().resolve(fileName);
            Files.createFile(filePath);
            parentNode.addChildResource(filePath);
            return new FSFile(resolveChildNode(webSession, parentNode, filePath));
        } catch (Exception e) {
            throw new DBWebException("Failed to create file: " + e.getMessage(), e);
        }
    }

    @Override
    public FSFile moveFile(
        @NotNull WebSession webSession,
        @NotNull String oldNodePath,
        @NotNull String parentNodePath
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            DBNPathBase oldNode = WebFSUtils.getNodeByPath(webSession, oldNodePath);
            DBNPathBase oldParentNode = (DBNPathBase) oldNode.getParentNode();
            String fileName = oldNode.getName();
            DBNPathBase parentNode = WebFSUtils.getNodeByPath(webSession, parentNodePath);
            Path parentPath = parentNode.getPath();
            if (!Files.isDirectory(parentPath)) {
                throw new DBException(MessageFormat.format("Node ''{0}'' is not a directory", parentPath));
            }
            Path to = Files.move(oldNode.getPath(), parentPath.resolve(fileName));
            // apply changes in navigator node
            oldParentNode.removeChildResource(oldNode.getPath());
            parentNode.addChildResource(to);
            return new FSFile(resolveChildNode(webSession, parentNode, to));
        } catch (NoSuchFileException e) {
            throw new DBWebException("File not found. Please refresh the catalog and check if file exists.");
        } catch (Exception e) {
            throw new DBWebException("Failed to move file: " + e.getMessage(), e);
        }
    }

    @Override
    public FSFile renameFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String newName
    ) throws DBWebException {
        validateEditPermissions(webSession);
        validateFilename(newName);
        try {
            DBNPathBase node = WebFSUtils.getNodeByPath(webSession, nodePath);
            node.rename(webSession.getProgressMonitor(), newName);
            return new FSFile(node);
        } catch (Exception e) {
            throw new DBWebException("Failed to move file: " + e.getMessage(), e);
        }
    }

    @Override
    public FSFile copyFile(
        @NotNull WebSession webSession,
        @NotNull String oldNodePath,
        @NotNull String parentNodePath
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            DBNPathBase oldNode = WebFSUtils.getNodeByPath(webSession, oldNodePath);
            String fileName = oldNode.getName();
            DBNPathBase parentNode = WebFSUtils.getNodeByPath(webSession, parentNodePath);
            Path parentPath = parentNode.getPath();
            if (!Files.isDirectory(parentPath)) {
                throw new DBException(MessageFormat.format("Node ''{0}'' is not a directory", parentPath));
            }
            Path to = Files.copy(oldNode.getPath(), parentPath.resolve(fileName));
            parentNode.addChildResource(to);
            return new FSFile(resolveChildNode(webSession, parentNode, to));
        } catch (Exception e) {
            throw new DBWebException("Failed to copy file: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public WebAsyncTaskInfo transferFiles(
        @NotNull WebSession webSession,
        @NotNull List<String> nodePaths,
        @NotNull String toParentNodePath,
        @NotNull String mode,
        boolean resume
    ) throws DBWebException {
        validateEditPermissions(webSession);
        if (nodePaths.isEmpty()) {
            throw new DBWebException("Nothing to transfer");
        }
        boolean move = mode.toUpperCase().contains("MOVE");
        String taskName = resume
            ? (move ? "Resume move" : "Resume copy")
            : (move ? "Move objects" : "Copy objects");
        return webSession.createAndRunAsyncTask(taskName, new WebAsyncTaskProcessor<String>() {
            @Override
            public void run(DBRProgressMonitor monitor) throws InvocationTargetException {
                FsTransferMonitor.set(monitor, resume);
                try {
                    DBNPathBase parentNode = WebFSUtils.getNodeByPath(webSession, toParentNodePath);
                    Path parentPath = parentNode.getPath();
                    if (!Files.isDirectory(parentPath)) {
                        throw new DBException(MessageFormat.format("Node ''{0}'' is not a directory", toParentNodePath));
                    }
                    int done = 0;
                    int skipped = 0;
                    int failed = 0;
                    monitor.beginTask(taskName, nodePaths.size());
                    for (String nodePath : nodePaths) {
                        if (monitor.isCancelled()) {
                            throw new InterruptedException("Transfer cancelled");
                        }
                        try {
                            if (transferOne(webSession, nodePath, parentNode, move, resume)) {
                                done++;
                            } else {
                                skipped++;
                            }
                        } catch (InterruptedException | InterruptedIOException e) {
                            throw e;
                        } catch (Exception e) {
                            failed++;
                            if (nodePaths.size() == 1) {
                                throw e;
                            }
                        }
                        String status = (move ? "Moved " : "Copied ") + done + "/" + nodePaths.size();
                        if (skipped > 0) {
                            status += ", skipped " + skipped;
                        }
                        if (failed > 0) {
                            status += " (" + failed + " failed)";
                        }
                        monitor.subTask(status);
                        monitor.worked(1);
                    }
                    result = done + " succeeded, " + skipped + " skipped, " + failed + " failed";
                    if (done == 0 && skipped == 0 && failed > 0) {
                        throw new DBException(result);
                    }
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                } finally {
                    FsTransferMonitor.clear();
                }
            }
        });
    }

    /** @return false when resume skipped an already-copied file */
    private boolean transferOne(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull DBNPathBase parentNode,
        boolean move,
        boolean resume
    ) throws Exception {
        DBNPathBase oldNode = WebFSUtils.getNodeByPath(webSession, nodePath);
        DBNPathBase oldParentNode = (DBNPathBase) oldNode.getParentNode();
        String fileName = oldNode.getName();
        Path parentPath = parentNode.getPath();
        Path dest = parentPath.resolve(fileName);
        boolean sourceFolder = Files.isDirectory(oldNode.getPath());
        if (resume && !sourceFolder && Files.exists(dest)) {
            if (move) {
                Files.delete(oldNode.getPath());
                if (oldParentNode != null) {
                    oldParentNode.removeChildResource(oldNode.getPath());
                }
            }
            return false;
        }
        Path to = move
            ? Files.move(oldNode.getPath(), dest)
            : Files.copy(oldNode.getPath(), dest);
        if (move && oldParentNode != null) {
            oldParentNode.removeChildResource(oldNode.getPath());
        }
        parentNode.addChildResource(to);
        resolveChildNode(webSession, parentNode, to);
        return true;
    }

    @NotNull
    @Override
    public FSFile createFolder(
        @NotNull WebSession webSession,
        @NotNull String parentPath,
        @NotNull String folderName
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            DBNPathBase parentNode = WebFSUtils.getNodeByPath(webSession, parentPath);
            if (!Files.isDirectory(parentNode.getPath())) {
                throw new DBException(MessageFormat.format("Node ''{0}'' is not a directory", parentPath));
            }
            Path folderPath = parentNode.getPath().resolve(folderName);
            Files.createDirectory(folderPath);
            parentNode.addChildResource(folderPath);
            return new FSFile(resolveChildNode(webSession, parentNode, folderPath));
        } catch (Exception e) {
            throw new DBWebException("Failed to create folder: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            DBNPathBase node = WebFSUtils.getNodeByPath(webSession, nodePath);
            Path path = node.getPath();
            Files.delete(path);
            DBNPathBase parentNode = (DBNPathBase) node.getParentNode();
            parentNode.removeChildResource(path);
            return true;
        } catch (Exception e) {
            throw new DBWebException("Failed to create folder: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public String getBucketPolicy(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException {
        try {
            return requireAdmin(webSession, nodePath).getBucketPolicy(bucketPath(webSession, nodePath));
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read bucket policy: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setBucketPolicy(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String policy
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).setBucketPolicy(bucketPath(webSession, nodePath), policy);
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to set bucket policy: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSBucketNotification getBucketNotification(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        try {
            FsBucketNotification notification = requireAdmin(webSession, nodePath)
                .getBucketNotification(bucketPath(webSession, nodePath));
            return new FSBucketNotification(notification.events(), notification.targetArn());
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read bucket notification: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setBucketNotification(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull List<String> events,
        @NotNull String targetArn
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).setBucketNotification(
                bucketPath(webSession, nodePath),
                events,
                targetArn
            );
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to set bucket notification: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean removeBucketNotification(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).removeBucketNotification(bucketPath(webSession, nodePath));
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to remove bucket notification: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSStackblazeContext getStackblazeContext(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        try {
            FsStackblazeContext context = requireAdmin(webSession, nodePath).stackblazeContext();
            return new FSStackblazeContext(context.pipeline(), context.phase(), context.instance());
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to resolve Stackblaze context: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public String getBucketVersioning(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException {
        try {
            return requireAdmin(webSession, nodePath).getBucketVersioning(bucketPath(webSession, nodePath));
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read bucket versioning: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setBucketVersioning(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String status
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).setBucketVersioning(bucketPath(webSession, nodePath), status);
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to set bucket versioning: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSBucketEncryption getBucketEncryption(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        try {
            FsBucketEncryption encryption = requireAdmin(webSession, nodePath)
                .getBucketEncryption(bucketPath(webSession, nodePath));
            return new FSBucketEncryption(encryption.algorithm(), encryption.kmsKeyId());
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read bucket encryption: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setBucketEncryption(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String algorithm,
        @Nullable String kmsKeyId
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).setBucketEncryption(
                bucketPath(webSession, nodePath),
                algorithm,
                kmsKeyId
            );
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to set bucket encryption: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean removeBucketEncryption(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).removeBucketEncryption(bucketPath(webSession, nodePath));
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to remove bucket encryption: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSTag[] getBucketTags(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException {
        try {
            return toFsTags(requireAdmin(webSession, nodePath).getBucketTags(bucketPath(webSession, nodePath)));
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read bucket tags: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setBucketTags(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull Map<String, String> tags
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).setBucketTags(bucketPath(webSession, nodePath), tags);
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to set bucket tags: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSTag[] getObjectTags(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException {
        try {
            return toFsTags(requireAdmin(webSession, nodePath).getObjectTags(bucketPath(webSession, nodePath)));
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read object tags: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setObjectTags(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull Map<String, String> tags
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).setObjectTags(bucketPath(webSession, nodePath), tags);
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to set object tags: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteObjectTags(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).deleteObjectTags(bucketPath(webSession, nodePath));
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to delete object tags: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSObjectInfo getObjectInfo(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @Nullable String versionId
    ) throws DBWebException {
        try {
            FsObjectInfo info = requireAdmin(webSession, nodePath)
                .getObjectInfo(bucketPath(webSession, nodePath), versionId);
            return new FSObjectInfo(
                info.size(),
                info.etag(),
                info.lastModified(),
                info.storageClass(),
                info.versionId(),
                info.encryption()
            );
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to read object info: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public FSObjectVersion[] listObjectVersions(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException {
        try {
            List<FsObjectVersion> versions = requireAdmin(webSession, nodePath)
                .listObjectVersions(bucketPath(webSession, nodePath));
            List<FSObjectVersion> mapped = new ArrayList<>();
            for (FsObjectVersion version : versions) {
                mapped.add(new FSObjectVersion(
                    version.versionId(),
                    version.latest(),
                    version.deleteMarker(),
                    version.size(),
                    version.etag(),
                    version.lastModified(),
                    version.storageClass()
                ));
            }
            return mapped.toArray(FSObjectVersion[]::new);
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to list object versions: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteObjectVersion(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String versionId
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).deleteObjectVersion(bucketPath(webSession, nodePath), versionId);
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to delete object version: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean restoreObjectVersion(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String versionId
    ) throws DBWebException {
        validateEditPermissions(webSession);
        try {
            requireAdmin(webSession, nodePath).restoreObjectVersion(bucketPath(webSession, nodePath), versionId);
            return true;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException("Failed to restore object version: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static FSTag[] toFsTags(@NotNull Map<String, String> tags) {
        return tags.entrySet().stream()
            .map(entry -> new FSTag(entry.getKey(), entry.getValue()))
            .toArray(FSTag[]::new);
    }

    @NotNull
    private FsBucketAdmin requireAdmin(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException {
        try {
            Path path = bucketPath(webSession, nodePath);
            FsBucketAdmin admin = FsBucketAdmin.of(path);
            if (admin == null) {
                throw new DBWebException("Bucket policies and events are only available on RustFS storage");
            }
            return admin;
        } catch (DBWebException e) {
            throw e;
        } catch (Exception e) {
            throw new DBWebException(e.getMessage(), e);
        }
    }

    @NotNull
    private Path bucketPath(@NotNull WebSession webSession, @NotNull String nodePath) throws DBException {
        return WebFSUtils.getNodeByPath(webSession, nodePath).getPath();
    }

    private void validateFilename(@NotNull String filename) throws DBWebException {
        Matcher matcher = FORBIDDEN_FILENAME_PATTERN.matcher(filename);

        if (matcher.find()) {
            throw new DBWebException(String.format("File %s contains forbidden symbols", filename));
        }
    }
    
    private void validateEditPermissions(@NotNull WebSession webSession) throws DBWebException {
        if (DBWorkbench.isDistributed() && !webSession.hasPermission(DBWConstants.PERMISSION_FS_RESOURCE_EDIT)) {
            throw new DBWebException("Permission denied");
        }
    }
}
