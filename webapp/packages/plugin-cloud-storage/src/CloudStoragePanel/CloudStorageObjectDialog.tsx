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

import { CloudStorageService } from '../CloudStorageService.js';
import { formatFileSize, getPathName } from '../pathUtils.js';
import type { IFSObjectInfo, IFSObjectVersion, IFSTag } from '../queries/queries.js';
import { CloudStorageTagsEditor } from './CloudStorageTagsEditor.js';
import style from './CloudStorageBucketDialog.module.css';

export type CloudStorageObjectTab = 'info' | 'tags' | 'versions';

export interface CloudStorageObjectPayload {
  nodePath: string;
  tab?: CloudStorageObjectTab;
}

export const CloudStorageObjectDialog: DialogComponent<CloudStorageObjectPayload> = observer(function CloudStorageObjectDialog({
  payload,
  resolveDialog,
  rejectDialog,
}) {
  const styles = useS(style);
  const translate = useTranslate();
  const cloudStorageService = useService(CloudStorageService);
  const [tab, setTab] = useState<CloudStorageObjectTab>(payload.tab ?? 'info');
  const [info, setInfo] = useState<IFSObjectInfo | null>(null);
  const [tags, setTags] = useState<IFSTag[]>([]);
  const [versions, setVersions] = useState<IFSObjectVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const name = getPathName(payload.nodePath);

  async function reload() {
    const [objectInfo, objectTags, objectVersions] = await Promise.all([
      cloudStorageService.getObjectInfo(payload.nodePath),
      cloudStorageService.getObjectTags(payload.nodePath),
      cloudStorageService.listObjectVersions(payload.nodePath).catch(() => []),
    ]);
    setInfo(objectInfo);
    setTags(objectTags);
    setVersions(objectVersions);
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    void reload()
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
  }, [cloudStorageService, payload.nodePath]);

  async function saveTags() {
    setBusy(true);
    setError(null);
    try {
      const next = tags.filter(tag => tag.key.trim());
      if (!next.length) {
        await cloudStorageService.deleteObjectTags(payload.nodePath);
      } else {
        await cloudStorageService.setObjectTags(payload.nodePath, next);
      }
      resolveDialog();
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function restore(versionId: string) {
    setBusy(true);
    setError(null);
    try {
      await cloudStorageService.restoreObjectVersion(payload.nodePath, versionId);
      await reload();
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function removeVersion(versionId: string) {
    setBusy(true);
    setError(null);
    try {
      await cloudStorageService.deleteObjectVersion(payload.nodePath, versionId);
      await reload();
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  return (
    <CommonDialogWrapper size="large" fixedWidth>
      <CommonDialogHeader
        title={translate(
          tab === 'tags'
            ? 'plugin_cloud_storage_object_tags'
            : tab === 'versions'
              ? 'plugin_cloud_storage_object_versions'
              : 'plugin_cloud_storage_object_properties',
        )}
        subTitle={name}
        onReject={rejectDialog}
      />
      <CommonDialogBody>
        <div className={s(styles, { tabs: true })}>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'info' })} onClick={() => setTab('info')}>
            {translate('plugin_cloud_storage_object_properties')}
          </button>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'tags' })} onClick={() => setTab('tags')}>
            {translate('plugin_cloud_storage_object_tags')}
          </button>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'versions' })} onClick={() => setTab('versions')}>
            {translate('plugin_cloud_storage_object_versions')}
          </button>
        </div>
        {loading && <Loader />}
        {error && <div className={s(styles, { error: true })}>{error}</div>}
        {!loading && tab === 'info' && info && (
          <>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_size')}: {formatFileSize(info.size)}
            </p>
            <p className={s(styles, { hint: true })}>ETag: {info.etag || '—'}</p>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_storage_class')}: {info.storageClass || 'STANDARD'}
            </p>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_bucket_encryption')}: {info.encryption || translate('plugin_cloud_storage_encryption_none')}
            </p>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_modified')}: {info.lastModified || '—'}
            </p>
            {info.versionId && (
              <p className={s(styles, { hint: true })}>
                {translate('plugin_cloud_storage_version_id')}: {info.versionId}
              </p>
            )}
          </>
        )}
        {!loading && tab === 'tags' && <CloudStorageTagsEditor tags={tags} onChange={setTags} disabled={busy} />}
        {!loading && tab === 'versions' && (
          <>
            <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_versions_hint')}</p>
            {!versions.length && <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_versions_empty')}</p>}
            {versions.map(version => (
              <div key={version.versionId ?? 'null'} className={s(styles, { wired: true })}>
                <span>
                  {version.deleteMarker ? translate('plugin_cloud_storage_delete_marker') : formatFileSize(version.size)}
                  {version.latest ? ` · ${translate('plugin_cloud_storage_version_latest')}` : ''}
                  {` · ${version.lastModified ?? ''}`}
                  {version.versionId ? ` · ${version.versionId}` : ''}
                </span>
                <span>
                  {!version.deleteMarker && version.versionId && (
                    <>
                      <Button
                        type="button"
                        variant="secondary"
                        disabled={busy}
                        onClick={() => cloudStorageService.downloadFile(payload.nodePath, name, version.versionId!)}
                      >
                        {translate('plugin_cloud_storage_download')}
                      </Button>
                      {!version.latest && (
                        <Button type="button" variant="secondary" disabled={busy} onClick={() => void restore(version.versionId!)}>
                          {translate('plugin_cloud_storage_version_restore')}
                        </Button>
                      )}
                    </>
                  )}
                  {version.versionId && (
                    <Button type="button" variant="secondary" disabled={busy} onClick={() => void removeVersion(version.versionId!)}>
                      {translate('ui_delete')}
                    </Button>
                  )}
                </span>
              </div>
            ))}
          </>
        )}
      </CommonDialogBody>
      <CommonDialogFooter>
        <Button type="button" variant="secondary" onClick={() => rejectDialog()}>
          {translate(tab === 'tags' ? 'ui_processing_cancel' : 'ui_close')}
        </Button>
        <Fill />
        {tab === 'tags' && (
          <Button type="button" disabled={loading || busy} onClick={() => void saveTags()}>
            {translate('ui_processing_save')}
          </Button>
        )}
      </CommonDialogFooter>
    </CommonDialogWrapper>
  );
});
