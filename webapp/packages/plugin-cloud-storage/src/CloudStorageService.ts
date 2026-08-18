/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { action, computed, makeObservable, observable, runInAction } from 'mobx';

import { UserDataService } from '@cloudbeaver/core-authentication';
import {
  ConnectionInfoActiveProjectKey,
  ConnectionInfoResource,
  type Connection,
  createConnectionParam,
} from '@cloudbeaver/core-connections';
import { injectable } from '@cloudbeaver/core-di';
import { ProjectsService } from '@cloudbeaver/core-projects';
import { CachedMapAllKey } from '@cloudbeaver/core-resource';
import { GraphQLService } from '@cloudbeaver/core-sdk';
import { ToolsPanelService } from '@cloudbeaver/plugin-tools-panel';

import {
  FS_LIST_FILE_SYSTEMS_QUERY,
  FS_LIST_FILES_QUERY,
  FS_CREATE_FOLDER_MUTATION,
  FS_DELETE_MUTATION,
  FS_READ_FILE_CONTENT_QUERY,
  type FsListFileSystemsResult,
  type FsListFilesResult,
  type FsReadFileContentResult,
  type IFSFile,
  type IFSFileSystem,
} from './queries/queries.js';

const cloudStorageSettingsKey = 'cloud-storage';

interface ISettings {
  active: boolean;
}

@injectable(() => [UserDataService, ToolsPanelService, GraphQLService, ConnectionInfoResource, ProjectsService])
export class CloudStorageService {
  readonly fileSystems = observable.array<IFSFileSystem>([]);
  readonly files = observable.array<IFSFile>([]);

  currentPath: string | null = null;
  loading = false;
  error: string | null = null;

  get settings() {
    return this.userDataService.getUserData(cloudStorageSettingsKey, getCloudStorageDefaultSettings);
  }

  get isActive(): boolean {
    return this.settings.active;
  }

  get disabled() {
    return this.toolsPanelService.disabled || !this.hasRustfsConnections;
  }

  get hasRustfsConnections(): boolean {
    return this.rustfsConnections.length > 0;
  }

  get rustfsConnections(): Connection[] {
    return this.connectionInfoResource
      .get(ConnectionInfoActiveProjectKey)
      .filter((connection): connection is Connection => !!connection && connection.driverId?.includes('rustfs'));
  }

  get projectId(): string | undefined {
    return this.projectsService.activeProjects[0]?.id ?? this.projectsService.userProject?.id;
  }

  get pathSegments(): string[] {
    if (!this.currentPath) {
      return [];
    }

    const withoutScheme = this.currentPath.replace(/^s3:\/\/[^/]+/, '');
    return withoutScheme.split('/').filter(Boolean);
  }

  constructor(
    private readonly userDataService: UserDataService,
    private readonly toolsPanelService: ToolsPanelService,
    private readonly graphQLService: GraphQLService,
    private readonly connectionInfoResource: ConnectionInfoResource,
    private readonly projectsService: ProjectsService,
  ) {
    makeObservable(this, {
      fileSystems: observable,
      files: observable,
      currentPath: observable,
      loading: observable,
      error: observable.ref,
      settings: computed,
      isActive: computed,
      disabled: computed,
      hasRustfsConnections: computed,
      rustfsConnections: computed,
      projectId: computed,
      pathSegments: computed,
      toggle: action,
      setCurrentPath: action,
      loadFileSystems: action,
      loadFiles: action,
    });
  }

  toggle(): void {
    this.settings.active = !this.settings.active;
  }

  setCurrentPath(path: string | null): void {
    this.currentPath = path;
  }

  findRustfsConnectionForPath(nodePath: string): Connection | undefined {
    const connectionId = this.nodeUriToFsPath(nodePath)?.match(/^s3:\/\/([^/]+)/)?.[1];
    if (!connectionId) {
      return undefined;
    }

    return this.rustfsConnections.find(connection => connection.id === connectionId);
  }

  /**
   * Real S3 URI (`s3://<bucket>/<key>`) for DuckDB and clipboard use. The
   * internal fs path prefixes the file-system (connection) id — strip it.
   */
  getS3Uri(nodePath: string): string {
    const fsPath = this.nodeUriToFsPath(nodePath) ?? nodePath;
    const match = fsPath.match(/^s3:\/\/[^/]+\/(.+)$/);
    return match ? `s3://${match[1]}` : fsPath;
  }

  /**
   * Navigator nodes under the virtual file system use
   * `node://dbvfs/<fsId>/<encoded-root-name>/<segments…>` URIs, while the fs
   * GraphQL API and the data servlet address entries by their canonical
   * `s3://<fsId>/<segments…>` path. Convert; returns null for non-storage URIs.
   */
  nodeUriToFsPath(uri: string): string | null {
    if (uri.startsWith('s3://')) {
      return uri;
    }
    const match = uri.match(/^node:\/\/dbvfs\/([^/]+)(?:\/[^/]+)?(?:\/(.+))?$/);
    if (!match) {
      return null;
    }
    const rest = match[2] ? '/' + match[2].split('/').map(decodeURIComponent).join('/') : '';
    return `s3://${match[1]}${rest}`;
  }

  async loadFileSystems(): Promise<void> {
    const projectId = this.projectId;
    if (!projectId) {
      return;
    }

    this.loading = true;
    this.error = null;

    try {
      await this.connectionInfoResource.load(CachedMapAllKey);
      const result = await this.graphQLService.client.request<FsListFileSystemsResult>(FS_LIST_FILE_SYSTEMS_QUERY, { projectId });

      runInAction(() => {
        this.fileSystems.replace(result.fileSystems);
        if (!this.currentPath && result.fileSystems.length > 0) {
          this.currentPath = result.fileSystems[0]!.nodePath;
        }
      });

      if (this.currentPath) {
        await this.loadFiles(this.currentPath);
      }
    } catch (exception: any) {
      runInAction(() => {
        this.error = exception?.message ?? String(exception);
      });
    } finally {
      runInAction(() => {
        this.loading = false;
      });
    }
  }

  async loadFiles(folderPath: string): Promise<void> {
    this.loading = true;
    this.error = null;

    try {
      const result = await this.graphQLService.client.request<FsListFilesResult>(FS_LIST_FILES_QUERY, { folderPath });

      runInAction(() => {
        this.currentPath = folderPath;
        this.files.replace(result.files);
      });
    } catch (exception: any) {
      runInAction(() => {
        this.error = exception?.message ?? String(exception);
      });
    } finally {
      runInAction(() => {
        this.loading = false;
      });
    }
  }

  async readFileContent(nodePath: string): Promise<string> {
    const result = await this.graphQLService.client.request<FsReadFileContentResult>(FS_READ_FILE_CONTENT_QUERY, { nodePath });
    return result.content;
  }

  /** Creates a bucket (root parent) or folder marker (bucket/folder parent). */
  async createFolder(parentPath: string, folderName: string): Promise<void> {
    await this.graphQLService.client.request(FS_CREATE_FOLDER_MUTATION, { parentPath, folderName });
  }

  /**
   * Delete through the file system API, not the navigator's navDeleteNodes:
   * that one calls checkProjectEditAccess, and file system nodes hang off the
   * navigator root rather than a project, so getOwnerProject() throws
   * "Node doesn't have owner project" before anything is deleted.
   */
  async deleteNode(nodePath: string): Promise<void> {
    await this.graphQLService.client.request(FS_DELETE_MUTATION, { nodePath });
  }

  getConnectionKey(connection: Connection) {
    return createConnectionParam(connection);
  }
}

function getCloudStorageDefaultSettings(): ISettings {
  return {
    active: false,
  };
}
