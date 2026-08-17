/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { observer } from 'mobx-react-lite';
import { useCallback, useEffect, useState } from 'react';

import { Loader, Pane, s, Split, TextPlaceholder, useS, useSplitUserState, useTranslate } from '@cloudbeaver/core-blocks';
import { useService } from '@cloudbeaver/core-di';

import { CloudStorageDuckDbService } from '../CloudStorageDuckDbService.js';
import { CloudStorageFileService } from '../CloudStorageFileService.js';
import { CloudStorageService } from '../CloudStorageService.js';
import type { IFSFile } from '../queries/queries.js';
import style from './CloudStoragePanel.module.css';

export const CloudStoragePanel = observer(function CloudStoragePanel() {
  const styles = useS(style);
  const translate = useTranslate();
  const splitState = useSplitUserState('cloud-storage');
  const cloudStorageService = useService(CloudStorageService);
  const cloudStorageFileService = useService(CloudStorageFileService);
  const cloudStorageDuckDbService = useService(CloudStorageDuckDbService);
  const [contextFile, setContextFile] = useState<IFSFile | null>(null);
  const [menuPosition, setMenuPosition] = useState<{ x: number; y: number } | null>(null);

  useEffect(() => {
    if (cloudStorageService.isActive) {
      cloudStorageService.loadFileSystems();
    }
  }, [cloudStorageService.isActive]);

  const openFolder = useCallback(
    (nodePath: string) => {
      cloudStorageService.loadFiles(nodePath);
    },
    [cloudStorageService],
  );

  const openFile = useCallback(
    async (file: IFSFile) => {
      if (file.folder) {
        openFolder(file.nodePath);
        return;
      }

      const s3Connection = cloudStorageService.findRustfsConnectionForPath(file.nodePath);
      if (!s3Connection) {
        return;
      }

      if (cloudStorageFileService.isSqlFile(file.name)) {
        await cloudStorageFileService.openSqlFile(file.nodePath);
      } else if (cloudStorageDuckDbService.isDataFile(file.name)) {
        await cloudStorageDuckDbService.openDataFile(
          cloudStorageService.getS3Uri(file.nodePath),
          s3Connection,
          file.name,
        );
      }
    },
    [cloudStorageService, cloudStorageFileService, cloudStorageDuckDbService, openFolder],
  );

  const copyUri = useCallback(
    async (file: IFSFile) => {
      await navigator.clipboard.writeText(cloudStorageService.getS3Uri(file.nodePath));
    },
    [cloudStorageService],
  );

  const downloadFile = useCallback(
    async (file: IFSFile) => {
      const content = await cloudStorageService.readFileContent(file.nodePath);
      const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.name;
      anchor.click();
      URL.revokeObjectURL(url);
    },
    [cloudStorageService],
  );

  const onContextMenu = useCallback((event: React.MouseEvent, file: IFSFile) => {
    if (file.folder) {
      return;
    }

    event.preventDefault();
    setContextFile(file);
    setMenuPosition({ x: event.clientX, y: event.clientY });
  }, []);

  const closeContextMenu = useCallback(() => {
    setContextFile(null);
    setMenuPosition(null);
  }, []);

  const navigateToRoot = useCallback(
    (fileSystemPath: string) => {
      openFolder(fileSystemPath);
    },
    [openFolder],
  );

  if (!cloudStorageService.isActive) {
    return <TextPlaceholder>{translate('plugin_cloud_storage_placeholder')}</TextPlaceholder>;
  }

  const currentPath = cloudStorageService.currentPath;
  const connectionId = currentPath?.match(/^s3:\/\/([^/]+)/)?.[1];

  return (
    <div className={s(styles, { cloudStorageWrapper: true })}>
      <Split {...splitState} disable={false}>
        <Pane className={s(styles, { treePane: true })} basis="25%">
          <div className={s(styles, { panelHeader: true })}>{translate('plugin_cloud_storage_buckets')}</div>
          <div className={s(styles, { treeList: true })}>
            {cloudStorageService.fileSystems.map(fileSystem => (
              <div
                key={fileSystem.id}
                className={s(styles, {
                  treeItem: true,
                  treeItemActive: currentPath?.startsWith(fileSystem.nodePath),
                })}
                title={fileSystem.nodePath}
                onClick={() => navigateToRoot(fileSystem.nodePath)}
              >
                {fileSystem.id}
              </div>
            ))}
            {cloudStorageService.fileSystems.length === 0 && !cloudStorageService.loading && (
              <div className={s(styles, { statusMessage: true })}>{translate('plugin_cloud_storage_no_buckets')}</div>
            )}
          </div>
        </Pane>
        <Pane className={s(styles, { contentPane: true })} main>
          {cloudStorageService.loading && <Loader />}
          {currentPath && (
            <div className={s(styles, { breadcrumb: true })}>
              <span
                className={s(styles, { breadcrumbItem: true })}
                onClick={() => {
                  const rootPath = currentPath.match(/^(s3:\/\/[^/]+)/)?.[1];
                  if (rootPath) {
                    openFolder(rootPath);
                  }
                }}
              >
                s3://{connectionId}
              </span>
              {cloudStorageService.pathSegments.map((segment, index) => {
                const prefix = currentPath.match(/^s3:\/\/[^/]+/)?.[0] ?? '';
                const segmentPath = `${prefix}/${cloudStorageService.pathSegments.slice(0, index + 1).join('/')}`;

                return (
                  <span key={segmentPath}>
                    <span className={s(styles, { breadcrumbSeparator: true })}>/</span>
                    <span className={s(styles, { breadcrumbItem: true })} onClick={() => openFolder(segmentPath)}>
                      {segment}
                    </span>
                  </span>
                );
              })}
            </div>
          )}
          <div className={s(styles, { panelHeader: true })}>{translate('plugin_cloud_storage_files')}</div>
          <div className={s(styles, { fileList: true })}>
            <div className={s(styles, { fileHeader: true })}>
              <span>{translate('plugin_cloud_storage_name')}</span>
              <span>{translate('plugin_cloud_storage_size')}</span>
              <span>{translate('plugin_cloud_storage_type')}</span>
            </div>
            {cloudStorageService.files.map(file => (
              <div
                key={file.nodePath}
                className={s(styles, { fileRow: true })}
                onDoubleClick={() => openFile(file)}
                onContextMenu={event => onContextMenu(event, file)}
              >
                <span className={s(styles, { fileName: true })} title={file.name}>
                  {file.name}
                </span>
                <span>{file.folder ? '—' : formatSize(file.length)}</span>
                <span>{file.folder ? translate('plugin_cloud_storage_folder') : translate('plugin_cloud_storage_file')}</span>
              </div>
            ))}
            {cloudStorageService.files.length === 0 && !cloudStorageService.loading && (
              <div className={s(styles, { statusMessage: true })}>{translate('plugin_cloud_storage_empty')}</div>
            )}
          </div>
        </Pane>
      </Split>
      {contextFile && menuPosition && (
        <>
          <div className={s(styles, { contextMenuOverlay: true })} onClick={closeContextMenu} />
          <div
            className={s(styles, { contextMenu: true })}
            style={{ top: menuPosition.y, left: menuPosition.x }}
          >
            <button type="button" className={s(styles, { contextMenuItem: true })} onClick={() => { openFile(contextFile); closeContextMenu(); }}>
              {translate('plugin_cloud_storage_open')}
            </button>
            <button type="button" className={s(styles, { contextMenuItem: true })} onClick={() => { copyUri(contextFile); closeContextMenu(); }}>
              {translate('plugin_cloud_storage_copy_uri')}
            </button>
            <button type="button" className={s(styles, { contextMenuItem: true })} onClick={() => { downloadFile(contextFile); closeContextMenu(); }}>
              {translate('plugin_cloud_storage_download')}
            </button>
          </div>
        </>
      )}
    </div>
  );
});

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }

  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }

  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
