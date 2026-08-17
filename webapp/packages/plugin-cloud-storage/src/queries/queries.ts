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
