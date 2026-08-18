/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package io.cloudbeaver.service.fs;

import io.cloudbeaver.DBWebException;
import io.cloudbeaver.model.WebAsyncTaskInfo;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.service.DBWService;
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

import java.util.List;
import java.util.Map;

/**
 * Web service API
 */
public interface DBWServiceFS extends DBWService {
    @NotNull
    FSFileSystem[] getAvailableFileSystems(@NotNull WebSession webSession, @NotNull String projectId)
        throws DBWebException;


    @NotNull
    FSFileSystem getFileSystem(
        @NotNull WebSession webSession,
        @NotNull String projectId,
        @NotNull String fileSystemId
    ) throws DBWebException;

    @NotNull
    FSFile getFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath
    ) throws DBWebException;

    @NotNull
    FSFile[] getFiles(
        @NotNull WebSession webSession,
        @NotNull String nodePath
    ) throws DBWebException;

    @NotNull
    String readFileContent(
        @NotNull WebSession webSession,
        @NotNull String nodePath
    ) throws DBWebException;

    FSFile writeFileContent(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String data,
        boolean forceOverwrite
    ) throws DBWebException;

    @NotNull
    FSFile createFile(
        @NotNull WebSession webSession,
        @NotNull String parentPath,
        @NotNull String fileName
    ) throws DBWebException;

    FSFile moveFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String parentNodePath
    ) throws DBWebException;

    FSFile renameFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String newName
    ) throws DBWebException;

    FSFile copyFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String parentNodePath
    ) throws DBWebException;

    @NotNull
    WebAsyncTaskInfo transferFiles(
        @NotNull WebSession webSession,
        @NotNull List<String> nodePaths,
        @NotNull String toParentNodePath,
        @NotNull String mode,
        boolean resume
    ) throws DBWebException;

    @NotNull
    FSFile createFolder(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String folderName
    ) throws DBWebException;

    boolean deleteFile(
        @NotNull WebSession webSession,
        @NotNull String nodePath
    ) throws DBWebException;

    @NotNull
    String getBucketPolicy(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    boolean setBucketPolicy(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String policy
    ) throws DBWebException;

    @NotNull
    FSBucketNotification getBucketNotification(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException;

    boolean setBucketNotification(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull List<String> events,
        @NotNull String targetArn
    ) throws DBWebException;

    boolean removeBucketNotification(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    @NotNull
    FSStackblazeContext getStackblazeContext(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException;

    @NotNull
    String getBucketVersioning(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    boolean setBucketVersioning(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String status
    ) throws DBWebException;

    @NotNull
    FSBucketEncryption getBucketEncryption(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException;

    boolean setBucketEncryption(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String algorithm,
        @Nullable String kmsKeyId
    ) throws DBWebException;

    boolean removeBucketEncryption(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    @NotNull
    FSTag[] getBucketTags(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    boolean setBucketTags(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull Map<String, String> tags
    ) throws DBWebException;

    @NotNull
    FSTag[] getObjectTags(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    boolean setObjectTags(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull Map<String, String> tags
    ) throws DBWebException;

    boolean deleteObjectTags(@NotNull WebSession webSession, @NotNull String nodePath) throws DBWebException;

    @NotNull
    FSObjectInfo getObjectInfo(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @Nullable String versionId
    ) throws DBWebException;

    @NotNull
    FSObjectVersion[] listObjectVersions(@NotNull WebSession webSession, @NotNull String nodePath)
        throws DBWebException;

    boolean deleteObjectVersion(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String versionId
    ) throws DBWebException;

    boolean restoreObjectVersion(
        @NotNull WebSession webSession,
        @NotNull String nodePath,
        @NotNull String versionId
    ) throws DBWebException;

}
