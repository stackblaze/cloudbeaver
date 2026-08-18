/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { importLazyComponent, RenameDialog } from '@cloudbeaver/core-blocks';
import { Bootstrap, injectable } from '@cloudbeaver/core-di';
import { CommonDialogService, DialogueStateResult } from '@cloudbeaver/core-dialogs';
import { NotificationService } from '@cloudbeaver/core-events';
import { DATA_CONTEXT_NAV_NODE, NavNodeManagerService, NavTreeResource, type NavNode } from '@cloudbeaver/core-navigation-tree';
import { GlobalConstants } from '@cloudbeaver/core-utils';
import { ACTION_DELETE, ACTION_DOWNLOAD, ACTION_NEW_FOLDER, ACTION_OPEN, ACTION_RENAME, ActionService, menuExtractItems, MenuService } from '@cloudbeaver/core-view';
import { MENU_NAV_TREE } from '@cloudbeaver/plugin-navigation-tree';
import { MENU_TOOLS, ToolsPanelService } from '@cloudbeaver/plugin-tools-panel';

import { ACTION_CLOUD_STORAGE_COPY_URI } from './Actions/ACTION_CLOUD_STORAGE_COPY_URI.js';
import { ACTION_CLOUD_STORAGE_CREATE_BUCKET } from './Actions/ACTION_CLOUD_STORAGE_CREATE_BUCKET.js';
import { ACTION_CLOUD_STORAGE_ENABLE } from './Actions/ACTION_CLOUD_STORAGE_ENABLE.js';
import { ACTION_CLOUD_STORAGE_UPLOAD } from './Actions/ACTION_CLOUD_STORAGE_UPLOAD.js';
import { CloudStorageDuckDbService } from './CloudStorageDuckDbService.js';
import { CloudStorageFileService } from './CloudStorageFileService.js';
import { CloudStorageService } from './CloudStorageService.js';

const CloudStoragePanel = importLazyComponent(() => import('./CloudStoragePanel/CloudStoragePanel.js').then(m => m.CloudStoragePanel));

@injectable(() => [
  ToolsPanelService,
  MenuService,
  ActionService,
  CloudStorageService,
  CloudStorageFileService,
  CloudStorageDuckDbService,
  NavTreeResource,
  NavNodeManagerService,
  CommonDialogService,
  NotificationService,
])
export class CloudStorageBootstrap extends Bootstrap {
  constructor(
    private readonly toolsPanelService: ToolsPanelService,
    private readonly menuService: MenuService,
    private readonly actionService: ActionService,
    private readonly cloudStorageService: CloudStorageService,
    private readonly cloudStorageFileService: CloudStorageFileService,
    private readonly cloudStorageDuckDbService: CloudStorageDuckDbService,
    private readonly navTreeResource: NavTreeResource,
    private readonly navNodeManagerService: NavNodeManagerService,
    private readonly commonDialogService: CommonDialogService,
    private readonly notificationService: NotificationService,
  ) {
    super();
  }

  /** s3://<fsId>/<segments…> for storage nodes, null otherwise. */
  private fsPath(node: NavNode | undefined): string | null {
    if (!node) {
      return null;
    }
    return this.cloudStorageService.nodeUriToFsPath(node.uri);
  }

  /** Below bucket level — an object or folder inside a bucket. */
  private isEntry(fsPath: string | null): fsPath is string {
    return !!fsPath && /^s3:\/\/[^/]+\/./.test(fsPath);
  }

  private isFile(node: NavNode, fsPath: string | null): boolean {
    return this.isEntry(fsPath) && !node.folder && !node.hasChildren;
  }

  /** The storage root (s3://<fsId>, no path) — where buckets are created. */
  private isStorageRoot(fsPath: string | null): fsPath is string {
    return !!fsPath && /^s3:\/\/[^/]+\/?$/.test(fsPath);
  }

  override register(): void {
    this.menuService.addCreator({
      menus: [MENU_TOOLS],
      getItems: (context, items) => [...items, ACTION_CLOUD_STORAGE_ENABLE],
      orderItems: (context, items) => {
        items.push(...menuExtractItems(items, [ACTION_CLOUD_STORAGE_ENABLE]));
        return items;
      },
    });

    this.actionService.addHandler({
      id: 'cloud-storage-base',
      actions: [ACTION_CLOUD_STORAGE_ENABLE],
      isChecked: () => this.cloudStorageService.isActive,
      isHidden: () => this.cloudStorageService.disabled,
      handler: (context, action) => {
        switch (action) {
          case ACTION_CLOUD_STORAGE_ENABLE: {
            this.cloudStorageService.toggle();
            break;
          }
        }
      },
    });

    // Storage file context menu. Refresh comes from the base nav-node handler.
    this.menuService.addCreator({
      menus: [MENU_NAV_TREE],
      contexts: [DATA_CONTEXT_NAV_NODE],
      isApplicable: context => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        return !!node && this.isFile(node, this.fsPath(node));
      },
      getItems: (context, items) => [...items, ACTION_OPEN, ACTION_DOWNLOAD, ACTION_CLOUD_STORAGE_COPY_URI, ACTION_RENAME, ACTION_DELETE],
    });

    // Buckets and folders: upload + delete. Applies to any container below the
    // file system root (the fs node itself maps to s3://<fsId> with no path).
    this.menuService.addCreator({
      menus: [MENU_NAV_TREE],
      contexts: [DATA_CONTEXT_NAV_NODE],
      isApplicable: context => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        const fsPath = this.fsPath(node);
        return !!node && this.isEntry(fsPath) && !this.isFile(node!, fsPath);
      },
      getItems: (context, items) => [...items, ACTION_CLOUD_STORAGE_UPLOAD, ACTION_NEW_FOLDER, ACTION_DELETE],
    });

    // Storage root: create buckets.
    this.menuService.addCreator({
      menus: [MENU_NAV_TREE],
      contexts: [DATA_CONTEXT_NAV_NODE],
      isApplicable: context => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        return !!node && this.isStorageRoot(this.fsPath(node));
      },
      getItems: (context, items) => [...items, ACTION_CLOUD_STORAGE_CREATE_BUCKET],
    });

    this.actionService.addHandler({
      id: 'cloud-storage-node-actions',
      actions: [
        ACTION_OPEN,
        ACTION_DOWNLOAD,
        ACTION_CLOUD_STORAGE_COPY_URI,
        ACTION_RENAME,
        ACTION_DELETE,
        ACTION_CLOUD_STORAGE_UPLOAD,
        ACTION_NEW_FOLDER,
        ACTION_CLOUD_STORAGE_CREATE_BUCKET,
      ],
      contexts: [DATA_CONTEXT_NAV_NODE],
      isActionApplicable: (context, action) => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        const fsPath = this.fsPath(node);
        if (!node || !fsPath) {
          return false;
        }
        if (action === ACTION_CLOUD_STORAGE_CREATE_BUCKET) {
          return this.isStorageRoot(fsPath);
        }
        if (!this.isEntry(fsPath)) {
          return false;
        }
        const file = this.isFile(node, fsPath);

        switch (action) {
          case ACTION_OPEN: {
            const name = node.name ?? '';
            return file && (this.cloudStorageFileService.isSqlFile(name) || this.cloudStorageDuckDbService.isDataFile(name));
          }
          case ACTION_DOWNLOAD:
          case ACTION_CLOUD_STORAGE_COPY_URI:
          case ACTION_RENAME:
            return file;
          case ACTION_CLOUD_STORAGE_UPLOAD:
          case ACTION_NEW_FOLDER:
            return !file;
          case ACTION_DELETE:
            return true;
          default:
            return false;
        }
      },
      handler: async (context, action) => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        const fsPath = this.fsPath(node);
        if (!node || !fsPath) {
          return;
        }
        const name = node.name ?? '';

        try {
          switch (action) {
            case ACTION_OPEN: {
              const s3Connection = this.cloudStorageService.findRustfsConnectionForPath(fsPath);
              if (!s3Connection) {
                return;
              }
              // Server-facing calls take the navigator uri — the s3:// alias
              // only resolves in sessions that materialized it via the fs API.
              if (this.cloudStorageFileService.isSqlFile(name)) {
                await this.cloudStorageFileService.openSqlFile(node.uri);
              } else if (this.cloudStorageDuckDbService.isDataFile(name)) {
                await this.cloudStorageDuckDbService.openDataFile(this.cloudStorageService.getS3Uri(node.uri), s3Connection, name);
              }
              break;
            }
            case ACTION_DOWNLOAD: {
              const anchor = document.createElement('a');
              anchor.href = GlobalConstants.absoluteServiceUrl('fs-data') + `?nodePath=${encodeURIComponent(node.uri)}`;
              anchor.download = name;
              anchor.click();
              break;
            }
            case ACTION_CLOUD_STORAGE_COPY_URI: {
              await navigator.clipboard.writeText(this.cloudStorageService.getS3Uri(node.uri));
              break;
            }
            case ACTION_RENAME: {
              const { status, result } = await this.commonDialogService.open(RenameDialog, {
                name,
                subTitle: name,
                objectName: node.nodeType || 'File',
                icon: node.icon,
                validation: newName => newName.trim().length > 0 && !newName.includes('/'),
              });
              if (status === DialogueStateResult.Resolved && result !== undefined && result !== name) {
                await this.navTreeResource.changeName(node, result);
              }
              break;
            }
            case ACTION_DELETE: {
              const parentUri = getParentNodeUri(node.uri);
              await this.cloudStorageService.deleteNode(node.uri);
              if (parentUri) {
                await this.navNodeManagerService.refreshTree(parentUri);
              }
              break;
            }
            case ACTION_CLOUD_STORAGE_UPLOAD: {
              await this.uploadTo(node, node.uri);
              break;
            }
            case ACTION_CLOUD_STORAGE_CREATE_BUCKET:
            case ACTION_NEW_FOLDER: {
              const bucket = action === ACTION_CLOUD_STORAGE_CREATE_BUCKET;
              const { status, result } = await this.commonDialogService.open(RenameDialog, {
                name: '',
                subTitle: bucket ? node.name : `${node.name ?? ''}/`,
                objectName: bucket ? 'Bucket' : 'Folder',
                icon: node.icon,
                validation: newName =>
                  bucket
                    ? /^[a-z0-9][a-z0-9.-]{2,62}$/.test(newName)
                    : newName.trim().length > 0 && !newName.includes('/'),
              });
              if (status === DialogueStateResult.Resolved && result) {
                await this.cloudStorageService.createFolder(node.uri, result);
                await this.navNodeManagerService.refreshTree(node.uri);
              }
              break;
            }
          }
        } catch (exception: any) {
          this.notificationService.logException(exception, 'plugin_cloud_storage_action_error');
        }
      },
    });

    this.toolsPanelService.tabsContainer.add({
      key: 'cloud-storage-tab',
      order: 1,
      name: 'plugin_cloud_storage_action_enable_label',
      isHidden: () => this.cloudStorageService.disabled || !this.cloudStorageService.isActive,
      onClose: () => this.cloudStorageService.toggle(),
      panel: () => CloudStoragePanel,
    });
  }

  private uploadTo(node: NavNode, parentNodePath: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.multiple = true;
      input.onchange = async () => {
        try {
          const files = Array.from(input.files ?? []);
          if (!files.length) {
            resolve();
            return;
          }
          const body = new FormData();
          body.append('variables', JSON.stringify({ toParentNodePath: parentNodePath }));
          for (const file of files) {
            body.append('files', file, file.name);
          }
          const response = await fetch(GlobalConstants.absoluteServiceUrl('fs-data'), { method: 'POST', body });
          if (!response.ok) {
            throw new Error(`Upload failed: HTTP ${response.status}`);
          }
          await this.navNodeManagerService.refreshTree(node.uri);
          resolve();
        } catch (exception) {
          reject(exception);
        }
      };
      input.click();
    });
  }
}

/** Parent node URI, so the tree can be refreshed where the deleted entry was. */
function getParentNodeUri(uri: string): string | null {
  const index = uri.lastIndexOf('/');
  if (index <= 0) {
    return null;
  }
  const parent = uri.slice(0, index);
  return parent.endsWith('//') ? null : parent;
}
