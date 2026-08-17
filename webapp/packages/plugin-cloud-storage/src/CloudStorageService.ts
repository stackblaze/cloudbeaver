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
    const connectionId = nodePath.match(/^s3:\/\/([^/]+)/)?.[1];
    if (!connectionId) {
      return undefined;
    }

    return this.rustfsConnections.find(connection => connection.id === connectionId);
  }

  getS3Uri(nodePath: string): string {
    return nodePath;
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

  getConnectionKey(connection: Connection) {
    return createConnectionParam(connection);
  }
}

function getCloudStorageDefaultSettings(): ISettings {
  return {
    active: false,
  };
}
