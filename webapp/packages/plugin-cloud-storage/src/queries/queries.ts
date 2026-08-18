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
