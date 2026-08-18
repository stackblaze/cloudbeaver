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
import { NotificationService } from '@cloudbeaver/core-events';
import { ProjectsService } from '@cloudbeaver/core-projects';
import { CachedMapAllKey } from '@cloudbeaver/core-resource';
import { AsyncTaskInfoService } from '@cloudbeaver/core-root';
import { GraphQLService, type AsyncTaskInfo } from '@cloudbeaver/core-sdk';
import { GlobalConstants } from '@cloudbeaver/core-utils';
import { ToolsPanelService } from '@cloudbeaver/plugin-tools-panel';

import { CloudStorageTransferSnackbar } from './CloudStoragePanel/CloudStorageTransferSnackbar.js';
import { formatFileSize, getParentNodeUri, getPathName } from './pathUtils.js';
import {
  MULTIPART_PART_CONCURRENCY,
  MULTIPART_THRESHOLD,
  partCountFor,
  partSizeFor,
  uploadItemsFromFiles,
  type ICloudStorageMultipartState,
  type ICloudStorageUploadItem,
} from './uploadUtils.js';
import {
  FS_LIST_FILE_SYSTEMS_QUERY,
  FS_LIST_FILES_QUERY,
  FS_CREATE_FOLDER_MUTATION,
  FS_DELETE_MUTATION,
  FS_COPY_MUTATION,
  FS_MOVE_MUTATION,
  FS_TRANSFER_MUTATION,
  FS_RENAME_MUTATION,
  FS_READ_FILE_CONTENT_QUERY,
  FS_GET_BUCKET_POLICY_QUERY,
  FS_SET_BUCKET_POLICY_MUTATION,
  FS_GET_BUCKET_NOTIFICATION_QUERY,
  FS_SET_BUCKET_NOTIFICATION_MUTATION,
  FS_REMOVE_BUCKET_NOTIFICATION_MUTATION,
  FS_GET_STACKBLAZE_CONTEXT_QUERY,
  FS_GET_BUCKET_VERSIONING_QUERY,
  FS_SET_BUCKET_VERSIONING_MUTATION,
  FS_GET_BUCKET_ENCRYPTION_QUERY,
  FS_SET_BUCKET_ENCRYPTION_MUTATION,
  FS_REMOVE_BUCKET_ENCRYPTION_MUTATION,
  FS_GET_BUCKET_TAGS_QUERY,
  FS_SET_BUCKET_TAGS_MUTATION,
  FS_GET_OBJECT_TAGS_QUERY,
  FS_SET_OBJECT_TAGS_MUTATION,
  FS_DELETE_OBJECT_TAGS_MUTATION,
  FS_GET_OBJECT_INFO_QUERY,
  FS_LIST_OBJECT_VERSIONS_QUERY,
  FS_DELETE_OBJECT_VERSION_MUTATION,
  FS_RESTORE_OBJECT_VERSION_MUTATION,
  type FSTransferMode,
  type FsTransferResult,
  type FsListFileSystemsResult,
  type FsListFilesResult,
  type FsReadFileContentResult,
  type IFSFile,
  type IFSFileSystem,
  type IFSBucketNotification,
  type IFSBucketEncryption,
  type IFSObjectInfo,
  type IFSObjectVersion,
  type IFSStackblazeContext,
  type IFSTag,
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

export interface ICloudStorageTransferJob {
  nodePaths: string[];
  toParentNodePath: string;
  mode: CloudStorageClipboardMode;
}

export interface ICloudStorageUploadJob {
  parentPath: string;
  items: ICloudStorageUploadItem[];
  completed: string[];
  multipart: Record<string, ICloudStorageMultipartState>;
}

const UPLOAD_CONCURRENCY = 3;

@injectable(() => [UserDataService, ToolsPanelService, GraphQLService, ConnectionInfoResource, ProjectsService, AsyncTaskInfoService, NotificationService])
export class CloudStorageService {
  readonly fileSystems = observable.array<IFSFileSystem>([]);
  readonly files = observable.array<IFSFile>([]);
  readonly selectedItems = observable.map<string, boolean>();

  currentPath: string | null = null;
  loading = false;
  error: string | null = null;
  filterQuery = '';
  clipboard: ICloudStorageClipboard | null = null;
  lastTransfer: ICloudStorageTransferJob | null = null;
  lastUpload: ICloudStorageUploadJob | null = null;
  resumeKind: 'upload' | 'transfer' | null = null;

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
    return this.isBucketPath(this.currentPath);
  }

  /** Path to pass to policy/events APIs: the current bucket, or a selected bucket at the store root. */
  get bucketSettingsPath(): string | null {
    if (!this.currentPath) {
      return null;
    }
    if (this.isStorageRoot) {
      const selected = this.selectedFiles;
      const only = selected[0];
      return selected.length === 1 && only && this.isBucketPath(only.nodePath) ? only.nodePath : null;
    }
    return this.currentPath;
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

  get canResume(): boolean {
    return this.resumeKind === 'upload' ? !!this.lastUpload : this.resumeKind === 'transfer' ? !!this.lastTransfer : false;
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
    private readonly asyncTaskInfoService: AsyncTaskInfoService,
    private readonly notificationService: NotificationService,
  ) {
    makeObservable(this, {
      fileSystems: observable,
      files: observable,
      currentPath: observable,
      loading: observable,
      error: observable.ref,
      filterQuery: observable,
      clipboard: observable.ref,
      lastTransfer: observable.ref,
      lastUpload: observable.ref,
      resumeKind: observable,
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
      bucketSettingsPath: computed,
      canUpload: computed,
      canCreateFolder: computed,
      canPasteHere: computed,
      canResume: computed,
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

  isBucketPath(nodePath: string | null | undefined): boolean {
    const fsPath = nodePath ? this.nodeUriToFsPath(nodePath) : null;
    return !!fsPath && /^s3:\/\/[^/]+\/[^/]+\/?$/.test(fsPath);
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

  async getBucketPolicy(nodePath: string): Promise<string> {
    const result = await this.graphQLService.client.request<{ policy: string }>(FS_GET_BUCKET_POLICY_QUERY, { nodePath });
    return result.policy || '{}';
  }

  async setBucketPolicy(nodePath: string, policy: string): Promise<void> {
    await this.graphQLService.client.request(FS_SET_BUCKET_POLICY_MUTATION, { nodePath, policy });
  }

  async getBucketNotification(nodePath: string): Promise<IFSBucketNotification> {
    const result = await this.graphQLService.client.request<{ notification: IFSBucketNotification }>(FS_GET_BUCKET_NOTIFICATION_QUERY, {
      nodePath,
    });
    return result.notification ?? { events: [] };
  }

  async setBucketNotification(nodePath: string, events: string[], targetArn?: string): Promise<void> {
    await this.graphQLService.client.request(FS_SET_BUCKET_NOTIFICATION_MUTATION, { nodePath, events, targetArn });
  }

  async removeBucketNotification(nodePath: string): Promise<void> {
    await this.graphQLService.client.request(FS_REMOVE_BUCKET_NOTIFICATION_MUTATION, { nodePath });
  }

  async getStackblazeContext(nodePath: string): Promise<IFSStackblazeContext> {
    const result = await this.graphQLService.client.request<{ context: IFSStackblazeContext }>(FS_GET_STACKBLAZE_CONTEXT_QUERY, {
      nodePath,
    });
    return result.context;
  }

  async getBucketVersioning(nodePath: string): Promise<string> {
    const result = await this.graphQLService.client.request<{ status: string }>(FS_GET_BUCKET_VERSIONING_QUERY, { nodePath });
    return result.status || 'Off';
  }

  async setBucketVersioning(nodePath: string, status: string): Promise<void> {
    await this.graphQLService.client.request(FS_SET_BUCKET_VERSIONING_MUTATION, { nodePath, status });
  }

  async getBucketEncryption(nodePath: string): Promise<IFSBucketEncryption> {
    const result = await this.graphQLService.client.request<{ encryption: IFSBucketEncryption }>(FS_GET_BUCKET_ENCRYPTION_QUERY, {
      nodePath,
    });
    return result.encryption ?? {};
  }

  async setBucketEncryption(nodePath: string, algorithm: string, kmsKeyId?: string): Promise<void> {
    await this.graphQLService.client.request(FS_SET_BUCKET_ENCRYPTION_MUTATION, { nodePath, algorithm, kmsKeyId });
  }

  async removeBucketEncryption(nodePath: string): Promise<void> {
    await this.graphQLService.client.request(FS_REMOVE_BUCKET_ENCRYPTION_MUTATION, { nodePath });
  }

  async getBucketTags(nodePath: string): Promise<IFSTag[]> {
    const result = await this.graphQLService.client.request<{ tags: IFSTag[] }>(FS_GET_BUCKET_TAGS_QUERY, { nodePath });
    return result.tags ?? [];
  }

  async setBucketTags(nodePath: string, tags: IFSTag[]): Promise<void> {
    await this.graphQLService.client.request(FS_SET_BUCKET_TAGS_MUTATION, { nodePath, tags });
  }

  async getObjectTags(nodePath: string): Promise<IFSTag[]> {
    const result = await this.graphQLService.client.request<{ tags: IFSTag[] }>(FS_GET_OBJECT_TAGS_QUERY, { nodePath });
    return result.tags ?? [];
  }

  async setObjectTags(nodePath: string, tags: IFSTag[]): Promise<void> {
    await this.graphQLService.client.request(FS_SET_OBJECT_TAGS_MUTATION, { nodePath, tags });
  }

  async deleteObjectTags(nodePath: string): Promise<void> {
    await this.graphQLService.client.request(FS_DELETE_OBJECT_TAGS_MUTATION, { nodePath });
  }

  async getObjectInfo(nodePath: string, versionId?: string): Promise<IFSObjectInfo> {
    const result = await this.graphQLService.client.request<{ info: IFSObjectInfo }>(FS_GET_OBJECT_INFO_QUERY, { nodePath, versionId });
    return result.info;
  }

  async listObjectVersions(nodePath: string): Promise<IFSObjectVersion[]> {
    const result = await this.graphQLService.client.request<{ versions: IFSObjectVersion[] }>(FS_LIST_OBJECT_VERSIONS_QUERY, {
      nodePath,
    });
    return result.versions ?? [];
  }

  async deleteObjectVersion(nodePath: string, versionId: string): Promise<void> {
    await this.graphQLService.client.request(FS_DELETE_OBJECT_VERSION_MUTATION, { nodePath, versionId });
  }

  async restoreObjectVersion(nodePath: string, versionId: string): Promise<void> {
    await this.graphQLService.client.request(FS_RESTORE_OBJECT_VERSION_MUTATION, { nodePath, versionId });
  }

  /** Creates a bucket (root parent) or folder marker (bucket/folder parent). */
  async createFolder(parentPath: string, folderName: string): Promise<string> {
    const result = await this.graphQLService.client.request<{ folder: { nodePath: string } }>(FS_CREATE_FOLDER_MUTATION, {
      parentPath,
      folderName,
    });
    return result.folder.nodePath;
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
    await this.transferNodes(nodePaths, toParentNodePath, 'copy');
  }

  async moveNodes(nodePaths: string[], toParentNodePath: string): Promise<void> {
    await this.transferNodes(nodePaths, toParentNodePath, 'move');
  }

  async resumeLastTransfer(): Promise<void> {
    const job = this.lastTransfer;
    if (!job) {
      return;
    }
    await this.transferNodes(job.nodePaths, job.toParentNodePath, job.mode, true);
  }

  async resumeLastJob(): Promise<void> {
    if (this.resumeKind === 'upload') {
      await this.resumeLastUpload();
      return;
    }
    await this.resumeLastTransfer();
  }

  async pasteTo(destUri: string): Promise<void> {
    const clip = this.clipboard;
    if (!clip || !this.canPasteTo(destUri)) {
      return;
    }

    await this.transferNodes(
      clip.items.map(item => item.nodeUri),
      destUri,
      clip.mode,
    );
    if (clip.mode === 'move' && !this.lastTransfer) {
      this.setClipboard(null);
    }
  }

  async transferNodes(
    nodePaths: string[],
    toParentNodePath: string,
    mode: CloudStorageClipboardMode,
    resume = false,
  ): Promise<void> {
    if (!nodePaths.length) {
      return;
    }

    const job: ICloudStorageTransferJob = { nodePaths, toParentNodePath, mode };
    this.lastTransfer = job;
    this.resumeKind = 'transfer';

    const gqlMode: FSTransferMode = mode === 'move' ? 'MOVE' : 'COPY';
    let cancelTask: (() => Promise<void>) | null = null;
    const { controller } = this.notificationService.processNotification(
      () => CloudStorageTransferSnackbar,
      {
        onCancel: () => {
          void cancelTask?.();
        },
        onResume: () => {
          void this.transferNodes(job.nodePaths, job.toParentNodePath, job.mode, true);
        },
      },
      {
        title: resume
          ? mode === 'move'
            ? 'plugin_cloud_storage_resume_move'
            : 'plugin_cloud_storage_resume_copy'
          : mode === 'move'
            ? 'plugin_cloud_storage_move'
            : 'plugin_cloud_storage_copy',
        message: String(nodePaths.length),
      },
    );

    const task = this.asyncTaskInfoService.create(async () => {
      const result = await this.graphQLService.client.request<FsTransferResult>(FS_TRANSFER_MUTATION, {
        nodePaths,
        toParentNodePath,
        mode: gqlMode,
        resume,
      });
      return result.taskInfo as AsyncTaskInfo;
    });

    cancelTask = () => this.asyncTaskInfoService.cancel(task.id);
    task.onStatusChange.addHandler(info => {
      if (info.status) {
        controller.setMessage(info.status);
      }
    });

    try {
      const info = await this.asyncTaskInfoService.run(task);
      await this.asyncTaskInfoService.remove(task.id);
      this.lastTransfer = null;
      this.resumeKind = null;
      controller.resolve(
        mode === 'move' ? 'plugin_cloud_storage_move_done' : 'plugin_cloud_storage_copy_done',
        typeof info.taskResult === 'string' ? info.taskResult : undefined,
      );
      if (this.currentPath) {
        await this.loadFiles(this.currentPath);
      }
    } catch (exception: any) {
      const cancelled = task.cancelled || /cancel/i.test(String(exception?.message ?? ''));
      controller.reject(
        exception,
        cancelled ? 'plugin_cloud_storage_transfer_stopped' : 'plugin_cloud_storage_action_error',
        cancelled ? 'plugin_cloud_storage_transfer_cancelled' : undefined,
      );
    }
  }

  downloadFile(nodePath: string, name: string, versionId?: string): void {
    const params = new URLSearchParams({ nodePath });
    if (versionId) {
      params.set('versionId', versionId);
    }
    const anchor = document.createElement('a');
    anchor.href = GlobalConstants.absoluteServiceUrl('fs-data') + `?${params.toString()}`;
    anchor.download = name;
    anchor.click();
  }

  async uploadFiles(parentPath: string, files: File[]): Promise<void> {
    await this.uploadItems(parentPath, uploadItemsFromFiles(files));
  }

  async resumeLastUpload(): Promise<void> {
    const job = this.lastUpload;
    if (!job) {
      return;
    }
    await this.uploadItems(job.parentPath, job.items, true);
  }

  async uploadItems(parentPath: string, items: ICloudStorageUploadItem[], resume = false): Promise<void> {
    if (!items.length) {
      return;
    }

    if (!resume && this.lastUpload?.multipart) {
      void this.abortLeftoverMultipart(this.lastUpload);
    }

    const completed = new Set(resume ? (this.lastUpload?.completed ?? []) : []);
    const multipart = resume ? { ...(this.lastUpload?.multipart ?? {}) } : {};
    const job: ICloudStorageUploadJob = { parentPath, items, completed: [...completed], multipart };
    this.lastUpload = job;
    this.resumeKind = 'upload';

    const abort = new AbortController();
    let cancelTask: (() => Promise<void>) | null = () => {
      abort.abort();
      return Promise.resolve();
    };

    const { controller } = this.notificationService.processNotification(
      () => CloudStorageTransferSnackbar,
      {
        onCancel: () => {
          void cancelTask?.();
        },
        onResume: () => {
          void this.uploadItems(job.parentPath, job.items, true);
        },
      },
      {
        title: resume ? 'plugin_cloud_storage_resume_upload' : 'plugin_cloud_storage_upload',
        message: `0/${items.length}`,
      },
    );

    const folderCache = new Map<string, Promise<string>>();
    const existingByParent = new Map<string, Promise<Set<string>>>();
    let done = 0;
    let skipped = 0;
    let failed = 0;
    let bytesDone = 0;
    const bytesTotal = items.reduce((sum, item) => sum + item.file.size, 0);

    const report = () => {
      job.completed = [...completed];
      let status = `Uploaded ${done}/${items.length}`;
      if (bytesTotal > MULTIPART_THRESHOLD) {
        status += ` · ${formatFileSize(bytesDone)} / ${formatFileSize(bytesTotal)}`;
      }
      if (skipped > 0) {
        status += `, skipped ${skipped}`;
      }
      if (failed > 0) {
        status += ` (${failed} failed)`;
      }
      controller.setMessage(status);
    };

    const existingNames = (folderPath: string) => {
      let pending = existingByParent.get(folderPath);
      if (!pending) {
        pending = this.listFolder(folderPath)
          .then(files => new Set(files.map(file => file.name)))
          .catch(() => new Set<string>());
        existingByParent.set(folderPath, pending);
      }
      return pending;
    };

    const uploadOne = async (item: ICloudStorageUploadItem) => {
      if (abort.signal.aborted) {
        throw new DOMException('Transfer cancelled', 'AbortError');
      }
      if (completed.has(item.relativePath)) {
        skipped++;
        bytesDone += item.file.size;
        report();
        return;
      }

      const destParent = await this.ensureUploadParent(parentPath, item.relativePath, folderCache);
      if (resume) {
        const existing = await existingNames(destParent);
        if (existing.has(item.file.name) && !job.multipart[item.relativePath]) {
          completed.add(item.relativePath);
          skipped++;
          bytesDone += item.file.size;
          report();
          return;
        }
      }

      await this.postUploadFile(destParent, item, job, resume, abort.signal, added => {
        bytesDone += added;
        report();
      });
      delete job.multipart[item.relativePath];
      completed.add(item.relativePath);
      done++;
      report();
    };

    try {
      await runPool(
        items,
        async item => {
          try {
            await uploadOne(item);
          } catch (exception) {
            if (abort.signal.aborted || (exception as { name?: string })?.name === 'AbortError') {
              throw exception;
            }
            failed++;
            report();
          }
        },
        UPLOAD_CONCURRENCY,
        abort.signal,
      );
      if (failed > 0 && done === 0 && skipped === 0) {
        throw new Error(`${failed} uploads failed`);
      }
      if (failed > 0) {
        throw new Error(`Uploaded ${done}, skipped ${skipped}, ${failed} failed`);
      }
      this.lastUpload = null;
      this.resumeKind = null;
      controller.resolve('plugin_cloud_storage_upload_done', `Uploaded ${done}, skipped ${skipped}`);
      if (this.currentPath) {
        await this.loadFiles(this.currentPath);
      }
    } catch (exception: any) {
      const cancelled = abort.signal.aborted || exception?.name === 'AbortError' || /cancel/i.test(String(exception?.message ?? ''));
      controller.reject(
        exception,
        cancelled ? 'plugin_cloud_storage_transfer_stopped' : 'plugin_cloud_storage_action_error',
        cancelled ? 'plugin_cloud_storage_transfer_cancelled' : undefined,
      );
    }
  }

  private async ensureUploadParent(
    rootPath: string,
    relativePath: string,
    cache: Map<string, Promise<string>>,
  ): Promise<string> {
    const folders = relativePath.split('/').filter(Boolean);
    folders.pop();
    let current = rootPath;
    for (const folder of folders) {
      const key = `${current}\n${folder}`;
      let pending = cache.get(key);
      if (!pending) {
        pending = this.ensureChildFolder(current, folder);
        cache.set(key, pending);
      }
      current = await pending;
    }
    return current;
  }

  private async ensureChildFolder(parentPath: string, folderName: string): Promise<string> {
    try {
      return await this.createFolder(parentPath, folderName);
    } catch {
      const files = await this.listFolder(parentPath);
      const existing = files.find(file => file.folder && file.name === folderName);
      if (existing) {
        return existing.nodePath;
      }
      throw new Error(`Failed to create folder ${folderName}`);
    }
  }

  private async postUploadFile(
    parentPath: string,
    item: ICloudStorageUploadItem,
    job: ICloudStorageUploadJob,
    resume: boolean,
    signal: AbortSignal,
    onBytes: (bytes: number) => void,
  ): Promise<void> {
    if (item.file.size > MULTIPART_THRESHOLD) {
      await this.postMultipartFile(parentPath, item, job, signal, onBytes);
      return;
    }
    const body = new FormData();
    body.append('variables', JSON.stringify({ toParentNodePath: parentPath, resume }));
    body.append('files', item.file, item.file.name);
    const response = await fetch(GlobalConstants.absoluteServiceUrl('fs-data'), { method: 'POST', body, signal });
    if (!response.ok) {
      throw new Error(`Upload failed: HTTP ${response.status}`);
    }
    onBytes(item.file.size);
  }

  private async postMultipartFile(
    parentPath: string,
    item: ICloudStorageUploadItem,
    job: ICloudStorageUploadJob,
    signal: AbortSignal,
    onBytes: (bytes: number) => void,
  ): Promise<void> {
    const partSize = partSizeFor(item.file.size);
    const totalParts = partCountFor(item.file.size, partSize);
    let state = job.multipart[item.relativePath];
    if (!state || state.destParent !== parentPath) {
      const started = await this.fsJson<{ uploadId: string; partSize: number }>(
        { action: 'multipartStart', toParentNodePath: parentPath, fileName: item.file.name },
        undefined,
        signal,
      );
      state = { uploadId: started.uploadId, destParent: parentPath, partSize, parts: [] };
      job.multipart[item.relativePath] = state;
    }

    const doneParts = new Set(state.parts.map(part => part.partNumber));
    for (const partNumber of doneParts) {
      const start = (partNumber - 1) * state.partSize;
      onBytes(Math.min(state.partSize, item.file.size - start));
    }
    const pending = Array.from({ length: totalParts }, (_, index) => index + 1).filter(partNumber => !doneParts.has(partNumber));

    await runPool(
      pending,
      async partNumber => {
        const start = (partNumber - 1) * state.partSize;
        const blob = item.file.slice(start, Math.min(start + state.partSize, item.file.size));
        const result = await this.fsJson<{ etag: string; partNumber: number }>(
          { action: 'multipartPart', toParentNodePath: parentPath, uploadId: state.uploadId, partNumber },
          { blob, name: `${item.file.name}.part${partNumber}` },
          signal,
        );
        state.parts.push({ partNumber: result.partNumber, etag: result.etag });
        onBytes(blob.size);
      },
      MULTIPART_PART_CONCURRENCY,
      signal,
    );

    state.parts.sort((a, b) => a.partNumber - b.partNumber);
    await this.fsJson(
      {
        action: 'multipartComplete',
        toParentNodePath: parentPath,
        uploadId: state.uploadId,
        parts: state.parts,
      },
      undefined,
      signal,
    );
  }

  private async abortLeftoverMultipart(job: ICloudStorageUploadJob): Promise<void> {
    await Promise.all(
      Object.values(job.multipart).map(state =>
        this.fsJson(
          { action: 'multipartAbort', toParentNodePath: state.destParent, uploadId: state.uploadId },
          undefined,
        ).catch(() => undefined),
      ),
    );
  }

  private async fsJson<T>(
    variables: Record<string, unknown>,
    file?: { blob: Blob; name: string },
    signal?: AbortSignal,
  ): Promise<T> {
    const body = new FormData();
    body.append('variables', JSON.stringify(variables));
    if (file) {
      body.append('files', file.blob, file.name);
    }
    const response = await fetch(GlobalConstants.absoluteServiceUrl('fs-data'), { method: 'POST', body, signal });
    if (!response.ok) {
      throw new Error(`Upload failed: HTTP ${response.status}`);
    }
    const text = await response.text();
    return (text ? JSON.parse(text) : {}) as T;
  }

  getConnectionKey(connection: Connection) {
    return createConnectionParam(connection);
  }
}

async function runPool<T>(
  items: T[],
  worker: (item: T) => Promise<void>,
  concurrency: number,
  signal: AbortSignal,
): Promise<void> {
  let index = 0;

  const next = async () => {
    while (index < items.length) {
      if (signal.aborted) {
        throw new DOMException('Transfer cancelled', 'AbortError');
      }
      const current = items[index++]!;
      await worker(current);
    }
  };

  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, next));
  if (signal.aborted) {
    throw new DOMException('Transfer cancelled', 'AbortError');
  }
}

function getCloudStorageDefaultSettings(): ISettings {
  return {
    active: false,
  };
}
