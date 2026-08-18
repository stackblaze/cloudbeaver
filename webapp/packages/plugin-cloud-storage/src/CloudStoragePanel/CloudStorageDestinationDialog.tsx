/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { observer } from 'mobx-react-lite';
import { useEffect, useState } from 'react';

import {
  Button,
  CommonDialogBody,
  CommonDialogFooter,
  CommonDialogHeader,
  CommonDialogWrapper,
  Fill,
  Loader,
  s,
  useS,
  useTranslate,
} from '@cloudbeaver/core-blocks';
import { useService } from '@cloudbeaver/core-di';
import type { DialogComponent } from '@cloudbeaver/core-dialogs';

import { CloudStorageService, type CloudStorageClipboardMode } from '../CloudStorageService.js';
import { getParentNodeUri, getPathName } from '../pathUtils.js';
import type { IFSFile } from '../queries/queries.js';
import style from './CloudStorageDestinationDialog.module.css';

export interface CloudStorageDestinationPayload {
  mode: CloudStorageClipboardMode;
  sourcePaths: string[];
}

export const CloudStorageDestinationDialog: DialogComponent<CloudStorageDestinationPayload, string> = observer(
  function CloudStorageDestinationDialog({ payload, resolveDialog, rejectDialog }) {
    const styles = useS(style);
    const translate = useTranslate();
    const cloudStorageService = useService(CloudStorageService);
    const [path, setPath] = useState(cloudStorageService.storageRootPath);
    const [files, setFiles] = useState<IFSFile[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
      if (!path) {
        return;
      }

      let cancelled = false;
      setLoading(true);
      setError(null);
      cloudStorageService
        .listFolder(path)
        .then(result => {
          if (!cancelled) {
            setFiles(result.filter(file => file.folder));
          }
        })
        .catch((exception: any) => {
          if (!cancelled) {
            setError(exception?.message ?? String(exception));
          }
        })
        .finally(() => {
          if (!cancelled) {
            setLoading(false);
          }
        });

      return () => {
        cancelled = true;
      };
    }, [path, cloudStorageService]);

    const root = cloudStorageService.storageRootPath;
    const destFsPath = path ? cloudStorageService.nodeUriToFsPath(path) : null;
    const destIsRoot = !!destFsPath && /^s3:\/\/[^/]+\/?$/.test(destFsPath);
    const canConfirm =
      !!path &&
      !destIsRoot &&
      !payload.sourcePaths.some(source => path === source || path.startsWith(`${source}/`));
    const parent = path && root && path !== root ? getParentNodeUri(path) : null;

    return (
      <CommonDialogWrapper size="medium" fixedWidth>
        <CommonDialogHeader
          title={payload.mode === 'move' ? 'plugin_cloud_storage_move_to' : 'plugin_cloud_storage_copy_to'}
          onReject={rejectDialog}
        />
        <CommonDialogBody>
          <div className={s(styles, { path: true })}>
            {path &&
              buildCrumbs(path, root).map((crumb, index) => (
                <span key={crumb}>
                  {index > 0 && ' / '}
                  <button type="button" className={s(styles, { crumb: true })} onClick={() => setPath(crumb)}>
                    {root && crumb === root ? translate('plugin_cloud_storage_action_enable_label') : getPathName(crumb)}
                  </button>
                </span>
              ))}
          </div>
          {parent && (
            <button type="button" className={s(styles, { row: true })} onClick={() => setPath(parent)}>
              ..
            </button>
          )}
          {loading && <Loader />}
          {error && <div className={s(styles, { error: true })}>{error}</div>}
          {!loading &&
            files.map(file => (
              <button key={file.nodePath} type="button" className={s(styles, { row: true })} onClick={() => setPath(file.nodePath)}>
                {file.name}
              </button>
            ))}
          {!loading && !error && files.length === 0 && (
            <div className={s(styles, { empty: true })}>{translate('plugin_cloud_storage_empty')}</div>
          )}
        </CommonDialogBody>
        <CommonDialogFooter>
          <Button type="button" variant="secondary" onClick={() => rejectDialog()}>
            {translate('ui_processing_cancel')}
          </Button>
          <Fill />
          <Button type="button" disabled={!canConfirm} onClick={() => path && resolveDialog(path)}>
            {translate(payload.mode === 'move' ? 'plugin_cloud_storage_move' : 'plugin_cloud_storage_copy')}
          </Button>
        </CommonDialogFooter>
      </CommonDialogWrapper>
    );
  },
);

function buildCrumbs(path: string, root: string | null): string[] {
  const crumbs: string[] = [];
  let current: string | null = path;
  while (current) {
    crumbs.unshift(current);
    if (root && current === root) {
      break;
    }
    current = getParentNodeUri(current);
    if (root && current && current.length < root.length) {
      break;
    }
  }
  return crumbs;
}
