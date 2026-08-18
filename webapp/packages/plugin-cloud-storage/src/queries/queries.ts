/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

export const FS_LIST_FILE_SYSTEMS_QUERY = `
  query fsListFileSystems($projectId: ID!) {
    fileSystems: fsListFileSystems(projectId: $projectId) {
      id
      nodePath
      requiredAuth
    }
  }
`;

export const FS_LIST_FILES_QUERY = `
  query fsListFiles($folderPath: String!) {
    files: fsListFiles(folderPath: $folderPath) {
      name
      length
      folder
      metaData
      nodePath
    }
  }
`;

export const FS_CREATE_FOLDER_MUTATION = `
  mutation fsCreateFolder($parentPath: String!, $folderName: String!) {
    folder: fsCreateFolder(parentPath: $parentPath, folderName: $folderName) {
      name
      nodePath
    }
  }
`;

export const FS_DELETE_MUTATION = `
  mutation fsDelete($nodePath: String!) {
    fsDelete(nodePath: $nodePath)
  }
`;

export const FS_COPY_MUTATION = `
  mutation fsCopy($nodePath: String!, $toParentNodePath: String!) {
    file: fsCopy(nodePath: $nodePath, toParentNodePath: $toParentNodePath) {
      name
      nodePath
    }
  }
`;

export const FS_MOVE_MUTATION = `
  mutation fsMove($nodePath: String!, $toParentNodePath: String!) {
    file: fsMove(nodePath: $nodePath, toParentNodePath: $toParentNodePath) {
      name
      nodePath
    }
  }
`;

export const FS_TRANSFER_MUTATION = `
  mutation fsTransfer($nodePaths: [String!]!, $toParentNodePath: String!, $mode: FSTransferMode!, $resume: Boolean) {
    taskInfo: fsTransfer(nodePaths: $nodePaths, toParentNodePath: $toParentNodePath, mode: $mode, resume: $resume) {
      id
      name
      running
      status
      taskResult
      error {
        message
        errorCode
        errorType
        stackTrace
      }
    }
  }
`;

export type FSTransferMode = 'COPY' | 'MOVE';

export interface FsAsyncTaskInfo {
  id: string;
  name?: string | null;
  running: boolean;
  status?: string | null;
  taskResult?: unknown;
  error?: {
    message?: string | null;
    errorCode?: string | null;
    errorType?: string | null;
    stackTrace?: string | null;
  } | null;
}

export interface FsTransferResult {
  taskInfo: FsAsyncTaskInfo;
}

export const FS_RENAME_MUTATION = `
  mutation fsRename($nodePath: String!, $newName: String!) {
    file: fsRename(nodePath: $nodePath, newName: $newName) {
      name
      nodePath
    }
  }
`;

export const FS_READ_FILE_CONTENT_QUERY = `
  query fsReadFileContentAsString($nodePath: String!) {
    content: fsReadFileContentAsString(nodePath: $nodePath)
  }
`;

export const FS_GET_BUCKET_POLICY_QUERY = `
  query fsGetBucketPolicy($nodePath: String!) {
    policy: fsGetBucketPolicy(nodePath: $nodePath)
  }
`;

export const FS_SET_BUCKET_POLICY_MUTATION = `
  mutation fsSetBucketPolicy($nodePath: String!, $policy: String!) {
    fsSetBucketPolicy(nodePath: $nodePath, policy: $policy)
  }
`;

export const FS_GET_BUCKET_NOTIFICATION_QUERY = `
  query fsGetBucketNotification($nodePath: String!) {
    notification: fsGetBucketNotification(nodePath: $nodePath) {
      events
      targetArn
    }
  }
`;

export const FS_SET_BUCKET_NOTIFICATION_MUTATION = `
  mutation fsSetBucketNotification($nodePath: String!, $events: [String!]!, $targetArn: String) {
    fsSetBucketNotification(nodePath: $nodePath, events: $events, targetArn: $targetArn)
  }
`;

export const FS_REMOVE_BUCKET_NOTIFICATION_MUTATION = `
  mutation fsRemoveBucketNotification($nodePath: String!) {
    fsRemoveBucketNotification(nodePath: $nodePath)
  }
`;

export const FS_GET_STACKBLAZE_CONTEXT_QUERY = `
  query fsGetStackblazeContext($nodePath: String!) {
    context: fsGetStackblazeContext(nodePath: $nodePath) {
      pipeline
      phase
      instance
    }
  }
`;

export interface IFSBucketNotification {
  events: string[];
  targetArn?: string | null;
}

export interface IFSStackblazeContext {
  pipeline?: string | null;
  phase?: string | null;
  instance?: string | null;
}

export interface IFSTag {
  key: string;
  value: string;
}

export interface IFSBucketEncryption {
  algorithm?: string | null;
  kmsKeyId?: string | null;
}

export interface IFSObjectInfo {
  size: number;
  etag?: string | null;
  lastModified?: string | null;
  storageClass?: string | null;
  versionId?: string | null;
  encryption?: string | null;
}

export interface IFSObjectVersion {
  versionId?: string | null;
  latest: boolean;
  deleteMarker: boolean;
  size: number;
  etag?: string | null;
  lastModified?: string | null;
  storageClass?: string | null;
}

export const FS_GET_BUCKET_VERSIONING_QUERY = `
  query fsGetBucketVersioning($nodePath: String!) {
    status: fsGetBucketVersioning(nodePath: $nodePath)
  }
`;

export const FS_SET_BUCKET_VERSIONING_MUTATION = `
  mutation fsSetBucketVersioning($nodePath: String!, $status: String!) {
    fsSetBucketVersioning(nodePath: $nodePath, status: $status)
  }
`;

export const FS_GET_BUCKET_ENCRYPTION_QUERY = `
  query fsGetBucketEncryption($nodePath: String!) {
    encryption: fsGetBucketEncryption(nodePath: $nodePath) {
      algorithm
      kmsKeyId
    }
  }
`;

export const FS_SET_BUCKET_ENCRYPTION_MUTATION = `
  mutation fsSetBucketEncryption($nodePath: String!, $algorithm: String!, $kmsKeyId: String) {
    fsSetBucketEncryption(nodePath: $nodePath, algorithm: $algorithm, kmsKeyId: $kmsKeyId)
  }
`;

export const FS_REMOVE_BUCKET_ENCRYPTION_MUTATION = `
  mutation fsRemoveBucketEncryption($nodePath: String!) {
    fsRemoveBucketEncryption(nodePath: $nodePath)
  }
`;

export const FS_GET_BUCKET_TAGS_QUERY = `
  query fsGetBucketTags($nodePath: String!) {
    tags: fsGetBucketTags(nodePath: $nodePath) {
      key
      value
    }
  }
`;

export const FS_SET_BUCKET_TAGS_MUTATION = `
  mutation fsSetBucketTags($nodePath: String!, $tags: [FSTagInput!]!) {
    fsSetBucketTags(nodePath: $nodePath, tags: $tags)
  }
`;

export const FS_GET_OBJECT_TAGS_QUERY = `
  query fsGetObjectTags($nodePath: String!) {
    tags: fsGetObjectTags(nodePath: $nodePath) {
      key
      value
    }
  }
`;

export const FS_SET_OBJECT_TAGS_MUTATION = `
  mutation fsSetObjectTags($nodePath: String!, $tags: [FSTagInput!]!) {
    fsSetObjectTags(nodePath: $nodePath, tags: $tags)
  }
`;

export const FS_DELETE_OBJECT_TAGS_MUTATION = `
  mutation fsDeleteObjectTags($nodePath: String!) {
    fsDeleteObjectTags(nodePath: $nodePath)
  }
`;

export const FS_GET_OBJECT_INFO_QUERY = `
  query fsGetObjectInfo($nodePath: String!, $versionId: String) {
    info: fsGetObjectInfo(nodePath: $nodePath, versionId: $versionId) {
      size
      etag
      lastModified
      storageClass
      versionId
      encryption
    }
  }
`;

export const FS_LIST_OBJECT_VERSIONS_QUERY = `
  query fsListObjectVersions($nodePath: String!) {
    versions: fsListObjectVersions(nodePath: $nodePath) {
      versionId
      latest
      deleteMarker
      size
      etag
      lastModified
      storageClass
    }
  }
`;

export const FS_DELETE_OBJECT_VERSION_MUTATION = `
  mutation fsDeleteObjectVersion($nodePath: String!, $versionId: String!) {
    fsDeleteObjectVersion(nodePath: $nodePath, versionId: $versionId)
  }
`;

export const FS_RESTORE_OBJECT_VERSION_MUTATION = `
  mutation fsRestoreObjectVersion($nodePath: String!, $versionId: String!) {
    fsRestoreObjectVersion(nodePath: $nodePath, versionId: $versionId)
  }
`;

export interface IFSFileSystem {
  id: string;
  nodePath: string;
  requiredAuth?: string | null;
}

export interface IFSFile {
  name: string;
  length: number;
  folder: boolean;
  metaData: Record<string, unknown>;
  nodePath: string;
}

export interface FsListFileSystemsResult {
  fileSystems: IFSFileSystem[];
}

export interface FsListFilesResult {
  files: IFSFile[];
}

export interface FsReadFileContentResult {
  content: string;
}
