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
import { GlobalConstants } from '@cloudbeaver/core-utils';
import { ToolsPanelService } from '@cloudbeaver/plugin-tools-panel';

import { getParentNodeUri, getPathName } from './pathUtils.js';
import {
  FS_LIST_FILE_SYSTEMS_QUERY,
  FS_LIST_FILES_QUERY,
  FS_CREATE_FOLDER_MUTATION,
  FS_DELETE_MUTATION,
  FS_COPY_MUTATION,
  FS_MOVE_MUTATION,
  FS_RENAME_MUTATION,
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

export type CloudStorageClipboardMode = 'copy' | 'move';

export interface ICloudStorageClipboardItem {
  nodeUri: string;
  parentUri: string | null;
  name: string;
}

export interface ICloudStorageClipboard {
  items: ICloudStorageClipboardItem[];
  mode: CloudStorageClipboardMode;
}

export interface ICloudStorageBreadcrumb {
  name: string;
  path: string;
}

@injectable(() => [UserDataService, ToolsPanelService, GraphQLService, ConnectionInfoResource, ProjectsService])
export class CloudStorageService {
  readonly fileSystems = observable.array<IFSFileSystem>([]);
  readonly files = observable.array<IFSFile>([]);
  readonly selectedItems = observable.map<string, boolean>();

  currentPath: string | null = null;
  loading = false;
  error: string | null = null;
  filterQuery = '';
  clipboard: ICloudStorageClipboard | null = null;

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

    const fsPath = this.nodeUriToFsPath(this.currentPath) ?? this.currentPath;
    const withoutScheme = fsPath.replace(/^s3:\/\/[^/]+/, '');
    return withoutScheme.split('/').filter(Boolean);
  }

  get storageRootPath(): string | null {
    return this.fileSystems[0]?.nodePath ?? null;
  }

  get currentFsPath(): string | null {
    return this.currentPath ? this.nodeUriToFsPath(this.currentPath) : null;
  }

  get isStorageRoot(): boolean {
    if (this.currentPath && this.storageRootPath && this.currentPath === this.storageRootPath) {
      return true;
    }
    const fsPath = this.currentFsPath;
    return !!fsPath && /^s3:\/\/[^/]+\/?$/.test(fsPath);
  }

  get isBucket(): boolean {
    const fsPath = this.currentFsPath;
    return !!fsPath && /^s3:\/\/[^/]+\/[^/]+\/?$/.test(fsPath);
  }

  get canUpload(): boolean {
    return !!this.currentPath && !this.isStorageRoot;
  }

  get canCreateFolder(): boolean {
    return !!this.currentPath;
  }

  get canPasteHere(): boolean {
    return !!this.currentPath && this.canPasteTo(this.currentPath);
  }

  get breadcrumbs(): ICloudStorageBreadcrumb[] {
    if (!this.currentPath) {
      return [];
    }

    const root = this.storageRootPath;
    const crumbs: ICloudStorageBreadcrumb[] = [];
    let path: string | null = this.currentPath;

    while (path) {
      crumbs.unshift({
        name: root && path === root ? (this.rustfsConnections[0]?.name ?? 'Storage') : getPathName(path),
        path,
      });
      if (root && path === root) {
        break;
      }
      path = getParentNodeUri(path);
      if (root && path && path.length < root.length) {
        break;
      }
    }

    return crumbs;
  }

  get visibleFiles(): IFSFile[] {
    const query = this.filterQuery.trim().toLowerCase();
    const files = this.files.slice().sort((a, b) => {
      if (a.folder !== b.folder) {
        return a.folder ? -1 : 1;
      }
      return a.name.localeCompare(b.name);
    });

    if (!query) {
      return files;
    }

    return files.filter(file => file.name.toLowerCase().includes(query));
  }

  get selectedFiles(): IFSFile[] {
    return this.files.filter(file => this.selectedItems.get(file.nodePath));
  }

  get selectedObjects(): IFSFile[] {
    return this.selectedFiles.filter(file => this.isObjectPath(file.nodePath));
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
      filterQuery: observable,
      clipboard: observable.ref,
      settings: computed,
      isActive: computed,
      disabled: computed,
      hasRustfsConnections: computed,
      rustfsConnections: computed,
      projectId: computed,
      pathSegments: computed,
      storageRootPath: computed,
      currentFsPath: computed,
      isStorageRoot: computed,
      isBucket: computed,
      canUpload: computed,
      canCreateFolder: computed,
      canPasteHere: computed,
      breadcrumbs: computed,
      visibleFiles: computed,
      selectedFiles: computed,
      selectedObjects: computed,
      toggle: action,
      setCurrentPath: action,
      setFilter: action,
      setClipboard: action,
      clearSelection: action,
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

  setFilter(query: string): void {
    this.filterQuery = query;
  }

  clearSelection(): void {
    this.selectedItems.clear();
  }

  isObjectPath(nodePath: string): boolean {
    const fsPath = this.nodeUriToFsPath(nodePath);
    return !!fsPath && /^s3:\/\/[^/]+\/[^/]+\/./.test(fsPath);
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
        this.selectedItems.clear();
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

  setClipboard(clipboard: ICloudStorageClipboard | null): void {
    this.clipboard = clipboard;
  }

  setClipboardFromFiles(files: IFSFile[], mode: CloudStorageClipboardMode): void {
    if (!files.length) {
      this.setClipboard(null);
      return;
    }

    this.setClipboard({
      mode,
      items: files.map(file => ({
        nodeUri: file.nodePath,
        parentUri: getParentNodeUri(file.nodePath),
        name: file.name,
      })),
    });
  }

  canPasteTo(destUri: string): boolean {
    if (!this.clipboard?.items.length) {
      return false;
    }

    return this.clipboard.items.every(item => destUri !== item.nodeUri && !destUri.startsWith(`${item.nodeUri}/`));
  }

  async listFolder(folderPath: string): Promise<IFSFile[]> {
    const result = await this.graphQLService.client.request<FsListFilesResult>(FS_LIST_FILES_QUERY, { folderPath });
    return result.files;
  }

  async renameNode(nodePath: string, newName: string): Promise<void> {
    await this.graphQLService.client.request(FS_RENAME_MUTATION, { nodePath, newName });
  }

  async copyNode(nodePath: string, toParentNodePath: string): Promise<void> {
    await this.graphQLService.client.request(FS_COPY_MUTATION, { nodePath, toParentNodePath });
  }

  async moveNode(nodePath: string, toParentNodePath: string): Promise<void> {
    await this.graphQLService.client.request(FS_MOVE_MUTATION, { nodePath, toParentNodePath });
  }

  async copyNodes(nodePaths: string[], toParentNodePath: string): Promise<void> {
    for (const nodePath of nodePaths) {
      await this.copyNode(nodePath, toParentNodePath);
    }
  }

  async moveNodes(nodePaths: string[], toParentNodePath: string): Promise<void> {
    for (const nodePath of nodePaths) {
      await this.moveNode(nodePath, toParentNodePath);
    }
  }

  async pasteTo(destUri: string): Promise<void> {
    const clip = this.clipboard;
    if (!clip || !this.canPasteTo(destUri)) {
      return;
    }

    if (clip.mode === 'move') {
      await this.moveNodes(
        clip.items.map(item => item.nodeUri),
        destUri,
      );
      this.setClipboard(null);
    } else {
      await this.copyNodes(
        clip.items.map(item => item.nodeUri),
        destUri,
      );
    }
  }

  downloadFile(nodePath: string, name: string): void {
    const anchor = document.createElement('a');
    anchor.href = GlobalConstants.absoluteServiceUrl('fs-data') + `?nodePath=${encodeURIComponent(nodePath)}`;
    anchor.download = name;
    anchor.click();
  }

  async uploadFiles(parentPath: string, files: File[]): Promise<void> {
    if (!files.length) {
      return;
    }

    const body = new FormData();
    body.append('variables', JSON.stringify({ toParentNodePath: parentPath }));
    for (const file of files) {
      body.append('files', file, file.name);
    }

    const response = await fetch(GlobalConstants.absoluteServiceUrl('fs-data'), { method: 'POST', body });
    if (!response.ok) {
      throw new Error(`Upload failed: HTTP ${response.status}`);
    }

    if (this.currentPath === parentPath) {
      await this.loadFiles(parentPath);
    }
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
