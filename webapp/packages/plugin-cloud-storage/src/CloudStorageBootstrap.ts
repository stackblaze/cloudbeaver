/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { importLazyComponent } from '@cloudbeaver/core-blocks';
import { Bootstrap, injectable } from '@cloudbeaver/core-di';
import { DATA_CONTEXT_NAV_NODE, type NavNode } from '@cloudbeaver/core-navigation-tree';
import { ACTION_DOWNLOAD, ACTION_OPEN, ActionService, menuExtractItems, MenuService } from '@cloudbeaver/core-view';
import { MENU_NAV_TREE } from '@cloudbeaver/plugin-navigation-tree';
import { MENU_TOOLS, ToolsPanelService } from '@cloudbeaver/plugin-tools-panel';

import { ACTION_CLOUD_STORAGE_COPY_URI } from './Actions/ACTION_CLOUD_STORAGE_COPY_URI.js';
import { ACTION_CLOUD_STORAGE_ENABLE } from './Actions/ACTION_CLOUD_STORAGE_ENABLE.js';
import { CloudStorageDuckDbService } from './CloudStorageDuckDbService.js';
import { CloudStorageFileService } from './CloudStorageFileService.js';
import { CloudStorageService } from './CloudStorageService.js';

const CloudStoragePanel = importLazyComponent(() => import('./CloudStoragePanel/CloudStoragePanel.js').then(m => m.CloudStoragePanel));

/** File node inside a virtual S3 file system (buckets/folders have children or the folder flag). */
function isCloudStorageFile(node: NavNode | undefined): node is NavNode {
  return !!node && node.uri.startsWith('s3://') && !node.folder && !node.hasChildren;
}

@injectable(() => [ToolsPanelService, MenuService, ActionService, CloudStorageService, CloudStorageFileService, CloudStorageDuckDbService])
export class CloudStorageBootstrap extends Bootstrap {
  constructor(
    private readonly toolsPanelService: ToolsPanelService,
    private readonly menuService: MenuService,
    private readonly actionService: ActionService,
    private readonly cloudStorageService: CloudStorageService,
    private readonly cloudStorageFileService: CloudStorageFileService,
    private readonly cloudStorageDuckDbService: CloudStorageDuckDbService,
  ) {
    super();
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

    // Context menu on storage file nodes: Open / Download / Copy URI
    // (Refresh comes from the base nav-node handler).
    this.menuService.addCreator({
      menus: [MENU_NAV_TREE],
      contexts: [DATA_CONTEXT_NAV_NODE],
      isApplicable: context => isCloudStorageFile(context.get(DATA_CONTEXT_NAV_NODE)),
      getItems: (context, items) => [...items, ACTION_OPEN, ACTION_DOWNLOAD, ACTION_CLOUD_STORAGE_COPY_URI],
    });

    this.actionService.addHandler({
      id: 'cloud-storage-file-actions',
      actions: [ACTION_OPEN, ACTION_DOWNLOAD, ACTION_CLOUD_STORAGE_COPY_URI],
      contexts: [DATA_CONTEXT_NAV_NODE],
      isActionApplicable: (context, action) => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        if (!isCloudStorageFile(node)) {
          return false;
        }
        if (action === ACTION_OPEN) {
          const name = node.name ?? '';
          return this.cloudStorageFileService.isSqlFile(name) || this.cloudStorageDuckDbService.isDataFile(name);
        }
        return true;
      },
      handler: async (context, action) => {
        const node = context.get(DATA_CONTEXT_NAV_NODE);
        if (!isCloudStorageFile(node)) {
          return;
        }
        const name = node.name ?? '';

        switch (action) {
          case ACTION_OPEN: {
            const s3Connection = this.cloudStorageService.findRustfsConnectionForPath(node.uri);
            if (!s3Connection) {
              return;
            }
            if (this.cloudStorageFileService.isSqlFile(name)) {
              await this.cloudStorageFileService.openSqlFile(node.uri);
            } else if (this.cloudStorageDuckDbService.isDataFile(name)) {
              await this.cloudStorageDuckDbService.openDataFile(this.cloudStorageService.getS3Uri(node.uri), s3Connection, name);
            }
            break;
          }
          case ACTION_DOWNLOAD: {
            const content = await this.cloudStorageService.readFileContent(node.uri);
            const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = name;
            anchor.click();
            URL.revokeObjectURL(url);
            break;
          }
          case ACTION_CLOUD_STORAGE_COPY_URI: {
            await navigator.clipboard.writeText(this.cloudStorageService.getS3Uri(node.uri));
            break;
          }
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
}
