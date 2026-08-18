/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { observer } from 'mobx-react-lite';
import { useCallback, useEffect, useState } from 'react';

import {
  ConfirmationDialog,
  Filter,
  IconOrImage,
  Loader,
  RenameDialog,
  s,
  Table,
  TableBody,
  TableColumnHeader,
  TableColumnValue,
  TableHeader,
  TableItem,
  TableItemSelect,
  TableSelect,
  TextPlaceholder,
  ToolsAction,
  ToolsPanel,
  useS,
  useTranslate,
} from '@cloudbeaver/core-blocks';
import { useService } from '@cloudbeaver/core-di';
import { CommonDialogService, DialogueStateResult } from '@cloudbeaver/core-dialogs';
import { NotificationService } from '@cloudbeaver/core-events';

import { CloudStorageDuckDbService } from '../CloudStorageDuckDbService.js';
import { CloudStorageFileService } from '../CloudStorageFileService.js';
import { CloudStorageService, type CloudStorageClipboardMode } from '../CloudStorageService.js';
import { formatFileSize } from '../pathUtils.js';
import type { IFSFile } from '../queries/queries.js';
import { CloudStorageDestinationDialog } from './CloudStorageDestinationDialog.js';
import style from './CloudStoragePanel.module.css';

interface IContextMenu {
  x: number;
  y: number;
  file: IFSFile;
}

export const CloudStoragePanel = observer(function CloudStoragePanel() {
  const styles = useS(style);
  const translate = useTranslate();
  const cloudStorageService = useService(CloudStorageService);
  const cloudStorageFileService = useService(CloudStorageFileService);
  const cloudStorageDuckDbService = useService(CloudStorageDuckDbService);
  const commonDialogService = useService(CommonDialogService);
  const notificationService = useService(NotificationService);
  const [menu, setMenu] = useState<IContextMenu | null>(null);

  useEffect(() => {
    if (cloudStorageService.isActive) {
      cloudStorageService.loadFileSystems();
    }
  }, [cloudStorageService.isActive]);

  const run = useCallback(
    async (work: () => Promise<void>) => {
      try {
        await work();
      } catch (exception: any) {
        notificationService.logException(exception, 'plugin_cloud_storage_action_error');
      }
    },
    [notificationService],
  );

  const refresh = useCallback(() => {
    if (cloudStorageService.currentPath) {
      return cloudStorageService.loadFiles(cloudStorageService.currentPath);
    }
    return cloudStorageService.loadFileSystems();
  }, [cloudStorageService]);

  const openFile = useCallback(
    async (file: IFSFile) => {
      if (file.folder) {
        await cloudStorageService.loadFiles(file.nodePath);
        return;
      }

      const s3Connection = cloudStorageService.findRustfsConnectionForPath(file.nodePath);
      if (!s3Connection) {
        return;
      }

      if (cloudStorageFileService.isSqlFile(file.name)) {
        await cloudStorageFileService.openSqlFile(file.nodePath);
      } else if (cloudStorageDuckDbService.isDataFile(file.name)) {
        await cloudStorageDuckDbService.openDataFile(cloudStorageService.getS3Uri(file.nodePath), s3Connection, file.name);
      }
    },
    [cloudStorageService, cloudStorageFileService, cloudStorageDuckDbService],
  );

  const upload = useCallback(async () => {
    if (!cloudStorageService.currentPath || !cloudStorageService.canUpload) {
      return;
    }

    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.onchange = () => {
      void run(() => cloudStorageService.uploadFiles(cloudStorageService.currentPath!, Array.from(input.files ?? [])));
    };
    input.click();
  }, [cloudStorageService, run]);

  const createFolder = useCallback(async () => {
    if (!cloudStorageService.currentPath) {
      return;
    }

    const bucket = cloudStorageService.isStorageRoot;
    const { status, result } = await commonDialogService.open(RenameDialog, {
      name: '',
      title: bucket ? 'plugin_cloud_storage_create_bucket' : 'plugin_cloud_storage_new_folder',
      objectName: bucket ? 'Bucket' : 'Folder',
      create: true,
      validation: newName => (bucket ? /^[a-z0-9][a-z0-9.-]{2,62}$/.test(newName) : newName.trim().length > 0 && !newName.includes('/')),
    });

    if (status === DialogueStateResult.Resolved && result) {
      await run(async () => {
        await cloudStorageService.createFolder(cloudStorageService.currentPath!, result);
        await refresh();
      });
    }
  }, [cloudStorageService, commonDialogService, refresh, run]);

  const transfer = useCallback(
    async (mode: CloudStorageClipboardMode, files = cloudStorageService.selectedObjects) => {
      if (!files.length) {
        return;
      }

      const { status, result } = await commonDialogService.open(CloudStorageDestinationDialog, {
        mode,
        sourcePaths: files.map(file => file.nodePath),
      });

      if (status !== DialogueStateResult.Resolved || !result) {
        return;
      }

      await run(async () => {
        const paths = files.map(file => file.nodePath);
        if (mode === 'move') {
          await cloudStorageService.moveNodes(paths, result);
        } else {
          await cloudStorageService.copyNodes(paths, result);
        }
        await refresh();
      });
    },
    [cloudStorageService, commonDialogService, refresh, run],
  );

  const paste = useCallback(async () => {
    if (!cloudStorageService.currentPath) {
      return;
    }

    await run(async () => {
      await cloudStorageService.pasteTo(cloudStorageService.currentPath!);
      await refresh();
    });
  }, [cloudStorageService, refresh, run]);

  const rename = useCallback(
    async (file?: IFSFile) => {
      const target = file ?? cloudStorageService.selectedFiles[0];
      if (!target) {
        return;
      }

      const { status, result } = await commonDialogService.open(RenameDialog, {
        name: target.name,
        objectName: target.folder ? 'Folder' : 'File',
        validation: newName => newName.trim().length > 0 && !newName.includes('/'),
      });

      if (status === DialogueStateResult.Resolved && result && result !== target.name) {
        await run(async () => {
          await cloudStorageService.renameNode(target.nodePath, result);
          await refresh();
        });
      }
    },
    [cloudStorageService, commonDialogService, refresh, run],
  );

  const remove = useCallback(
    async (files = cloudStorageService.selectedFiles) => {
      if (!files.length) {
        return;
      }

      const { status } = await commonDialogService.open(ConfirmationDialog, {
        title: 'ui_delete',
        message: 'plugin_cloud_storage_delete_confirm',
        subTitle: files.map(file => file.name).join(', '),
      });

      if (status !== DialogueStateResult.Rejected) {
        await run(async () => {
          for (const file of files) {
            await cloudStorageService.deleteNode(file.nodePath);
          }
          await refresh();
        });
      }
    },
    [cloudStorageService, commonDialogService, refresh, run],
  );

  const download = useCallback(
    (files = cloudStorageService.selectedFiles.filter(file => !file.folder)) => {
      for (const file of files) {
        cloudStorageService.downloadFile(file.nodePath, file.name);
      }
    },
    [cloudStorageService],
  );

  const copyUri = useCallback(
    async (file: IFSFile) => {
      await navigator.clipboard.writeText(cloudStorageService.getS3Uri(file.nodePath));
    },
    [cloudStorageService],
  );

  const handleDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      if (!cloudStorageService.canUpload || !cloudStorageService.currentPath) {
        return;
      }

      const files = Array.from(event.dataTransfer.files);
      if (files.length) {
        void run(() => cloudStorageService.uploadFiles(cloudStorageService.currentPath!, files));
      }
    },
    [cloudStorageService, run],
  );

  if (!cloudStorageService.isActive) {
    return <TextPlaceholder>{translate('plugin_cloud_storage_placeholder')}</TextPlaceholder>;
  }

  const selected = cloudStorageService.selectedFiles;
  const selectedObjects = cloudStorageService.selectedObjects;
  const selectedFilesOnly = selected.filter(file => !file.folder);
  const fileKeys = cloudStorageService.visibleFiles.map(file => file.nodePath);

  return (
    <div className={s(styles, { cloudStorageWrapper: true })}>
      <ToolsPanel minHeight bottomBorder>
        <ToolsAction
          icon="/icons/import.svg"
          title={translate('plugin_cloud_storage_upload')}
          disabled={!cloudStorageService.canUpload}
          onClick={upload}
        >
          {translate('plugin_cloud_storage_upload')}
        </ToolsAction>
        <ToolsAction
          icon="/icons/folder_sm.svg"
          title={translate(cloudStorageService.isStorageRoot ? 'plugin_cloud_storage_create_bucket' : 'plugin_cloud_storage_new_folder')}
          disabled={!cloudStorageService.canCreateFolder}
          onClick={createFolder}
        >
          {translate(cloudStorageService.isStorageRoot ? 'plugin_cloud_storage_create_bucket' : 'plugin_cloud_storage_new_folder')}
        </ToolsAction>
        <ToolsAction
          icon="/icons/export.svg"
          title={translate('plugin_cloud_storage_download')}
          disabled={!selectedFilesOnly.length}
          onClick={() => download()}
        >
          {translate('plugin_cloud_storage_download')}
        </ToolsAction>
        <ToolsAction icon="copy" title={translate('plugin_cloud_storage_copy')} disabled={!selectedObjects.length} onClick={() => transfer('copy')}>
          {translate('plugin_cloud_storage_copy')}
        </ToolsAction>
        <ToolsAction title={translate('plugin_cloud_storage_move')} disabled={!selectedObjects.length} onClick={() => transfer('move')}>
          {translate('plugin_cloud_storage_move')}
        </ToolsAction>
        <ToolsAction title={translate('plugin_cloud_storage_paste')} disabled={!cloudStorageService.canPasteHere} onClick={paste}>
          {translate('plugin_cloud_storage_paste')}
        </ToolsAction>
        <ToolsAction title={translate('ui_rename')} disabled={selected.length !== 1} onClick={() => rename()}>
          {translate('ui_rename')}
        </ToolsAction>
        <ToolsAction icon="trash" viewBox="0 0 24 24" title={translate('ui_delete')} disabled={!selected.length} onClick={() => remove()}>
          {translate('ui_delete')}
        </ToolsAction>
        <ToolsAction icon="refresh" viewBox="0 0 24 24" title={translate('ui_refresh')} onClick={() => run(refresh)}>
          {translate('ui_refresh')}
        </ToolsAction>
        <Filter
          className={s(styles, { filter: true })}
          value={cloudStorageService.filterQuery}
          placeholder={translate('plugin_cloud_storage_search')}
          smallSize
          onChange={value => cloudStorageService.setFilter(value)}
        />
      </ToolsPanel>

      <div className={s(styles, { breadcrumb: true })}>
        {cloudStorageService.breadcrumbs.map((crumb, index) => (
          <span key={crumb.path} className={s(styles, { breadcrumbItemWrap: true })}>
            {index > 0 && <span className={s(styles, { breadcrumbSeparator: true })}>/</span>}
            <button
              type="button"
              className={s(styles, { breadcrumbItem: true })}
              disabled={crumb.path === cloudStorageService.currentPath}
              onClick={() => run(() => cloudStorageService.loadFiles(crumb.path))}
            >
              {crumb.name}
            </button>
          </span>
        ))}
      </div>

      <div
        className={s(styles, { fileList: true })}
        onDragOver={event => {
          if (cloudStorageService.canUpload) {
            event.preventDefault();
          }
        }}
        onDrop={handleDrop}
      >
        {cloudStorageService.loading && <Loader className={s(styles, { loader: true })} />}
        {cloudStorageService.error && <div className={s(styles, { statusMessage: true })}>{cloudStorageService.error}</div>}
        {!cloudStorageService.loading && !cloudStorageService.visibleFiles.length && (
          <div className={s(styles, { statusMessage: true })}>
            {translate(cloudStorageService.isStorageRoot ? 'plugin_cloud_storage_no_buckets' : 'plugin_cloud_storage_empty')}
          </div>
        )}
        {!cloudStorageService.loading && !!cloudStorageService.visibleFiles.length && (
          <Table keys={fileKeys} selectedItems={cloudStorageService.selectedItems}>
            <TableHeader fixed>
              <TableColumnHeader min>
                <TableSelect />
              </TableColumnHeader>
              <TableColumnHeader>{translate('plugin_cloud_storage_name')}</TableColumnHeader>
              <TableColumnHeader>{translate('plugin_cloud_storage_size')}</TableColumnHeader>
              <TableColumnHeader>{translate('plugin_cloud_storage_type')}</TableColumnHeader>
            </TableHeader>
            <TableBody>
              {cloudStorageService.visibleFiles.map(file => (
                <TableItem
                  key={file.nodePath}
                  item={file.nodePath}
                  onDoubleClick={() => run(() => openFile(file))}
                  onClick={() => setMenu(null)}
                >
                  <TableColumnValue centerContent>
                    <TableItemSelect />
                  </TableColumnValue>
                  <TableColumnValue
                    title={file.name}
                    onContextMenu={event => {
                      event.preventDefault();
                      setMenu({ x: event.clientX, y: event.clientY, file });
                    }}
                  >
                    <span className={s(styles, { fileName: true })}>
                      <IconOrImage icon={file.folder ? '/icons/folder_sm.svg' : 'document'} className={s(styles, { fileIcon: true })} />
                      {file.name}
                    </span>
                  </TableColumnValue>
                  <TableColumnValue>{file.folder ? '—' : formatFileSize(file.length)}</TableColumnValue>
                  <TableColumnValue>
                    {translate(file.folder ? 'plugin_cloud_storage_folder' : 'plugin_cloud_storage_file')}
                  </TableColumnValue>
                </TableItem>
              ))}
            </TableBody>
          </Table>
        )}
      </div>

      {menu && (
        <>
          <div className={s(styles, { contextMenuOverlay: true })} onClick={() => setMenu(null)} />
          <div className={s(styles, { contextMenu: true })} style={{ top: menu.y, left: menu.x }}>
            {(menu.file.folder ||
              cloudStorageFileService.isSqlFile(menu.file.name) ||
              cloudStorageDuckDbService.isDataFile(menu.file.name)) && (
              <button
                type="button"
                className={s(styles, { contextMenuItem: true })}
                onClick={() => {
                  setMenu(null);
                  void run(() => openFile(menu.file));
                }}
              >
                {translate('plugin_cloud_storage_open')}
              </button>
            )}
            {!menu.file.folder && (
              <button
                type="button"
                className={s(styles, { contextMenuItem: true })}
                onClick={() => {
                  setMenu(null);
                  download([menu.file]);
                }}
              >
                {translate('plugin_cloud_storage_download')}
              </button>
            )}
            {cloudStorageService.isObjectPath(menu.file.nodePath) && (
              <>
                <button
                  type="button"
                  className={s(styles, { contextMenuItem: true })}
                  onClick={() => {
                    setMenu(null);
                    void transfer('copy', [menu.file]);
                  }}
                >
                  {translate('plugin_cloud_storage_copy')}
                </button>
                <button
                  type="button"
                  className={s(styles, { contextMenuItem: true })}
                  onClick={() => {
                    setMenu(null);
                    void transfer('move', [menu.file]);
                  }}
                >
                  {translate('plugin_cloud_storage_move')}
                </button>
              </>
            )}
            {menu.file.folder && cloudStorageService.canPasteTo(menu.file.nodePath) && (
              <button
                type="button"
                className={s(styles, { contextMenuItem: true })}
                onClick={() => {
                  setMenu(null);
                  void run(async () => {
                    await cloudStorageService.pasteTo(menu.file.nodePath);
                    await refresh();
                  });
                }}
              >
                {translate('plugin_cloud_storage_paste')}
              </button>
            )}
            <button
              type="button"
              className={s(styles, { contextMenuItem: true })}
              onClick={() => {
                setMenu(null);
                void rename(menu.file);
              }}
            >
              {translate('ui_rename')}
            </button>
            <button
              type="button"
              className={s(styles, { contextMenuItem: true })}
              onClick={() => {
                setMenu(null);
                void copyUri(menu.file);
              }}
            >
              {translate('plugin_cloud_storage_copy_uri')}
            </button>
            <button
              type="button"
              className={s(styles, { contextMenuItem: true })}
              onClick={() => {
                setMenu(null);
                void remove([menu.file]);
              }}
            >
              {translate('ui_delete')}
            </button>
          </div>
        </>
      )}
    </div>
  );
});
