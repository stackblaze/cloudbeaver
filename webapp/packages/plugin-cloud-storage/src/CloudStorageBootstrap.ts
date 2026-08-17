/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { importLazyComponent } from '@cloudbeaver/core-blocks';
import { Bootstrap, injectable } from '@cloudbeaver/core-di';
import { ActionService, menuExtractItems, MenuService } from '@cloudbeaver/core-view';
import { MENU_TOOLS, ToolsPanelService } from '@cloudbeaver/plugin-tools-panel';

import { ACTION_CLOUD_STORAGE_ENABLE } from './Actions/ACTION_CLOUD_STORAGE_ENABLE.js';
import { CloudStorageService } from './CloudStorageService.js';

const CloudStoragePanel = importLazyComponent(() => import('./CloudStoragePanel/CloudStoragePanel.js').then(m => m.CloudStoragePanel));

@injectable(() => [ToolsPanelService, MenuService, ActionService, CloudStorageService])
export class CloudStorageBootstrap extends Bootstrap {
  constructor(
    private readonly toolsPanelService: ToolsPanelService,
    private readonly menuService: MenuService,
    private readonly actionService: ActionService,
    private readonly cloudStorageService: CloudStorageService,
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
