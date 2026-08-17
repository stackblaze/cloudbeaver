/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { observer } from 'mobx-react-lite';
import { useCallback, useEffect } from 'react';

import { s, TextPlaceholder, useS, useTranslate } from '@cloudbeaver/core-blocks';
import { useService } from '@cloudbeaver/core-di';
import type { NavNode } from '@cloudbeaver/core-navigation-tree';
import { ElementsTreeLoader, NavigationTreeService } from '@cloudbeaver/plugin-navigation-tree';

import { CloudStorageDuckDbService } from '../CloudStorageDuckDbService.js';
import { CloudStorageFileService } from '../CloudStorageFileService.js';
import { CloudStorageService } from '../CloudStorageService.js';
import style from './CloudStoragePanel.module.css';

/** DBNFileSystems navigator node — parent of all virtual file systems. */
const DBVFS_ROOT = 'node://dbvfs';

export const CloudStoragePanel = observer(function CloudStoragePanel() {
  const styles = useS(style);
  const translate = useTranslate();
  const cloudStorageService = useService(CloudStorageService);
  const cloudStorageFileService = useService(CloudStorageFileService);
  const cloudStorageDuckDbService = useService(CloudStorageDuckDbService);
  const navTreeService = useService(NavigationTreeService);

  useEffect(() => {
    if (cloudStorageService.isActive) {
      cloudStorageService.loadFileSystems();
    }
  }, [cloudStorageService.isActive]);

  /** Tree open (double-click / enter): open SQL files in the editor, data files in DuckDB. */
  const handleNodeOpen = useCallback(
    async (node: NavNode, folder: boolean) => {
      if (folder || node.hasChildren) {
        return;
      }

      const s3Connection = cloudStorageService.findRustfsConnectionForPath(node.uri);
      if (!s3Connection) {
        return;
      }

      if (cloudStorageFileService.isSqlFile(node.name ?? '')) {
        await cloudStorageFileService.openSqlFile(node.uri);
      } else if (cloudStorageDuckDbService.isDataFile(node.name ?? '')) {
        await cloudStorageDuckDbService.openDataFile(cloudStorageService.getS3Uri(node.uri), s3Connection, node.name ?? '');
      }
    },
    [cloudStorageService, cloudStorageFileService, cloudStorageDuckDbService],
  );

  if (!cloudStorageService.isActive) {
    return <TextPlaceholder>{translate('plugin_cloud_storage_placeholder')}</TextPlaceholder>;
  }

  // With a single file system (the common case — one rustfs connection), root
  // the tree at it so the first visible level is the storage itself, not the
  // internal dbvfs id.
  const root = cloudStorageService.fileSystems.length === 1 ? cloudStorageService.fileSystems[0]!.nodePath : DBVFS_ROOT;

  return (
    <div className={s(styles, { cloudStorageWrapper: true })}>
      <ElementsTreeLoader
        root={root}
        className={s(styles, { tree: true })}
        getChildren={navTreeService.getChildren}
        loadChildren={navTreeService.loadNestedNodes}
        emptyPlaceholder={() => (
          <div className={s(styles, { statusMessage: true })}>{translate('plugin_cloud_storage_no_buckets')}</div>
        )}
        onOpen={handleNodeOpen}
      />
    </div>
  );
});
