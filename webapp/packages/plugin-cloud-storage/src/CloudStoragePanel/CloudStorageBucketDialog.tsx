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
import { getPathName } from '../pathUtils.js';
import type { IFSBucketEncryption, IFSStackblazeContext, IFSTag } from '../queries/queries.js';
import { CloudStorageTagsEditor } from './CloudStorageTagsEditor.js';
import {
  addEventTrigger,
  deleteEventTrigger,
  enableRustfsEvents,
  filterTypeOf,
  getRustfsEventsStatus,
  KUBERO_WEBHOOK_ARN,
  listFunctions,
  listTriggers,
  type StackblazeFunction,
} from '../stackblazeApi.js';
import style from './CloudStorageBucketDialog.module.css';

export type CloudStorageBucketTab = 'policy' | 'events' | 'versioning' | 'encryption' | 'tags';

export interface CloudStorageBucketPayload {
  nodePath: string;
  tab?: CloudStorageBucketTab;
}

type EventKind = 'created' | 'removed';

type WiredTrigger = {
  fn: string;
  app?: string;
  filterType: string;
  triggerName: string;
};

const PRIVATE_POLICY = `{
  "Version": "2012-10-17",
  "Statement": []
}`;

function publicReadPolicy(bucket: string): string {
  return JSON.stringify(
    {
      Version: '2012-10-17',
      Statement: [
        {
          Sid: 'PublicRead',
          Effect: 'Allow',
          Principal: '*',
          Action: ['s3:GetObject'],
          Resource: [`arn:aws:s3:::${bucket}/*`],
        },
      ],
    },
    null,
    2,
  );
}

function prettyPolicy(policyText: string): string {
  try {
    return JSON.stringify(JSON.parse(policyText || '{}'), null, 2);
  } catch {
    return policyText || '{}';
  }
}

function filterTypeFor(kind: EventKind): string {
  return kind === 'removed' ? 'com.kubero.addon.object.removed' : 'com.kubero.addon.object.created';
}

function s3EventFor(kind: EventKind): string {
  return kind === 'removed' ? 's3:ObjectRemoved:*' : 's3:ObjectCreated:*';
}

export const CloudStorageBucketDialog: DialogComponent<CloudStorageBucketPayload> = observer(function CloudStorageBucketDialog({
  payload,
  resolveDialog,
  rejectDialog,
}) {
  const styles = useS(style);
  const translate = useTranslate();
  const cloudStorageService = useService(CloudStorageService);
  const [tab, setTab] = useState<CloudStorageBucketTab>(payload.tab ?? 'policy');
  const [policy, setPolicy] = useState('{}');
  const [versioning, setVersioning] = useState('Off');
  const [encryption, setEncryption] = useState<IFSBucketEncryption>({});
  const [kmsKeyId, setKmsKeyId] = useState('');
  const [tags, setTags] = useState<IFSTag[]>([]);
  const [hasCreated, setHasCreated] = useState(false);
  const [hasRemoved, setHasRemoved] = useState(false);
  const [eventKind, setEventKind] = useState<EventKind>('created');
  const [context, setContext] = useState<IFSStackblazeContext | null>(null);
  const [functions, setFunctions] = useState<StackblazeFunction[]>([]);
  const [selectedFn, setSelectedFn] = useState('');
  const [wired, setWired] = useState<WiredTrigger[]>([]);
  const [eventsEnabled, setEventsEnabled] = useState(false);
  const [targetArn, setTargetArn] = useState(KUBERO_WEBHOOK_ARN);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [apiHint, setApiHint] = useState<string | null>(null);

  const bucket = getPathName(payload.nodePath);

  const loadWired = useCallback(
    async (fns: StackblazeFunction[], stackblaze: IFSStackblazeContext, created: boolean, removed: boolean) => {
      if (!stackblaze.pipeline || !stackblaze.phase || (!created && !removed)) {
        setWired([]);
        return;
      }
      const linked: WiredTrigger[] = [];
      await Promise.all(
        fns.map(async fn => {
          const triggers = await listTriggers(stackblaze.pipeline!, stackblaze.phase!, fn.name);
          for (const trigger of triggers) {
            if (trigger.type === 'cron') {
              continue;
            }
            const typ = filterTypeOf(trigger);
            if ((typ === 'com.kubero.addon.object.created' && created) || (typ === 'com.kubero.addon.object.removed' && removed)) {
              linked.push({ fn: fn.name, app: fn.app, filterType: typ, triggerName: trigger.name });
            }
          }
        }),
      );
      setWired(linked);
    },
    [],
  );

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    void (async () => {
      try {
        const [policyText, notification, stackblaze, versionStatus, enc, bucketTags] = await Promise.all([
          cloudStorageService.getBucketPolicy(payload.nodePath),
          cloudStorageService.getBucketNotification(payload.nodePath),
          cloudStorageService.getStackblazeContext(payload.nodePath),
          cloudStorageService.getBucketVersioning(payload.nodePath),
          cloudStorageService.getBucketEncryption(payload.nodePath),
          cloudStorageService.getBucketTags(payload.nodePath),
        ]);
        if (cancelled) {
          return;
        }
        setPolicy(prettyPolicy(policyText));
        setVersioning(versionStatus);
        setEncryption(enc);
        setKmsKeyId(enc.kmsKeyId ?? '');
        setTags(bucketTags);
        const events = notification?.events ?? [];
        const created = events.some(event => /ObjectCreated/i.test(event));
        const removed = events.some(event => /ObjectRemoved/i.test(event));
        setHasCreated(created);
        setHasRemoved(removed);
        if (notification?.targetArn) {
          setTargetArn(notification.targetArn);
        }
        setContext(stackblaze);
        if (stackblaze.pipeline && stackblaze.phase) {
          try {
            const [fns, status] = await Promise.all([
              listFunctions(stackblaze.pipeline, stackblaze.phase),
              stackblaze.instance
                ? getRustfsEventsStatus(stackblaze.pipeline, stackblaze.phase, stackblaze.instance)
                : Promise.resolve({ enabled: false }),
            ]);
            if (cancelled) {
              return;
            }
            setFunctions(fns);
            setEventsEnabled(!!status.enabled);
            if (status.arn) {
              setTargetArn(status.arn);
            }
            setSelectedFn(fns[0]?.name ?? '');
            await loadWired(fns, stackblaze, created, removed);
          } catch (exception: any) {
            if (!cancelled) {
              setApiHint(exception?.message ?? translate('plugin_cloud_storage_events_api_hint'));
            }
          }
        }
      } catch (exception: any) {
        if (!cancelled) {
          setError(exception?.message ?? String(exception));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [cloudStorageService, loadWired, payload.nodePath, translate]);

  async function saveTags() {
    setBusy(true);
    setError(null);
    try {
      await cloudStorageService.setBucketTags(
        payload.nodePath,
        tags.filter(tag => tag.key.trim()),
      );
      resolveDialog();
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function applyVersioning(status: 'Enabled' | 'Suspended') {
    setBusy(true);
    setError(null);
    try {
      await cloudStorageService.setBucketVersioning(payload.nodePath, status);
      setVersioning(status);
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function applyEncryption(algorithm: 'AES256' | 'aws:kms' | 'none') {
    setBusy(true);
    setError(null);
    try {
      if (algorithm === 'none') {
        await cloudStorageService.removeBucketEncryption(payload.nodePath);
        setEncryption({});
      } else {
        await cloudStorageService.setBucketEncryption(payload.nodePath, algorithm, algorithm === 'aws:kms' ? kmsKeyId : undefined);
        setEncryption({ algorithm, kmsKeyId: algorithm === 'aws:kms' ? kmsKeyId : undefined });
      }
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function savePolicy() {
    setBusy(true);
    setError(null);
    try {
      JSON.parse(policy);
      await cloudStorageService.setBucketPolicy(payload.nodePath, policy);
      resolveDialog();
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function enableEvents() {
    if (!context?.pipeline || !context.phase || !context.instance) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const status = await enableRustfsEvents(context.pipeline, context.phase, context.instance);
      setEventsEnabled(true);
      if (status.arn) {
        setTargetArn(status.arn);
      }
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
      setApiHint(translate('plugin_cloud_storage_events_api_hint'));
    } finally {
      setBusy(false);
    }
  }

  async function ensureBucketNotification(kind: EventKind): Promise<{ created: boolean; removed: boolean }> {
    const created = kind === 'created' || hasCreated;
    const removed = kind === 'removed' || hasRemoved;
    const events = [...(created ? ['s3:ObjectCreated:*'] : []), ...(removed ? ['s3:ObjectRemoved:*'] : [])];
    await cloudStorageService.setBucketNotification(payload.nodePath, events, targetArn || KUBERO_WEBHOOK_ARN);
    setHasCreated(created);
    setHasRemoved(removed);
    return { created, removed };
  }

  async function connectFunction() {
    if (!context?.pipeline || !context.phase || !selectedFn) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (context.instance && !eventsEnabled) {
        try {
          const status = await enableRustfsEvents(context.pipeline, context.phase, context.instance);
          setEventsEnabled(true);
          if (status.arn) {
            setTargetArn(status.arn);
          }
        } catch (exception: any) {
          setApiHint(exception?.message ?? translate('plugin_cloud_storage_events_api_hint'));
        }
      }
      const notify = await ensureBucketNotification(eventKind);
      const filterType = filterTypeFor(eventKind);
      try {
        const triggers = await listTriggers(context.pipeline, context.phase, selectedFn);
        if (!triggers.some(trigger => trigger.type !== 'cron' && filterTypeOf(trigger) === filterType)) {
          await addEventTrigger(context.pipeline, context.phase, selectedFn, filterType);
        }
        await loadWired(functions, context, notify.created, notify.removed);
      } catch (exception: any) {
        setApiHint(exception?.message ?? translate('plugin_cloud_storage_events_api_hint'));
      }
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function disconnectFunction(item: WiredTrigger) {
    if (!context?.pipeline || !context.phase) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await deleteEventTrigger(context.pipeline, context.phase, item.fn, item.triggerName);
      const remaining = wired.filter(row => !(row.fn === item.fn && row.filterType === item.filterType));
      setWired(remaining);
      if (!remaining.length) {
        await cloudStorageService.removeBucketNotification(payload.nodePath);
        setHasCreated(false);
        setHasRemoved(false);
      }
    } catch (exception: any) {
      setError(exception?.message ?? String(exception));
    } finally {
      setBusy(false);
    }
  }

  const contextLabel = context?.pipeline ? `${context.pipeline}/${context.phase}/${context.instance ?? ''}` : '';

  return (
    <CommonDialogWrapper size="large" fixedWidth>
      <CommonDialogHeader
        title={translate(
          tab === 'events'
            ? 'plugin_cloud_storage_bucket_events'
            : tab === 'versioning'
              ? 'plugin_cloud_storage_bucket_versioning'
              : tab === 'encryption'
                ? 'plugin_cloud_storage_bucket_encryption'
                : tab === 'tags'
                  ? 'plugin_cloud_storage_bucket_tags'
                  : 'plugin_cloud_storage_bucket_policy',
        )}
        subTitle={bucket}
        onReject={rejectDialog}
      />
      <CommonDialogBody>
        <div className={s(styles, { tabs: true })}>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'policy' })} onClick={() => setTab('policy')}>
            {translate('plugin_cloud_storage_bucket_policy')}
          </button>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'events' })} onClick={() => setTab('events')}>
            {translate('plugin_cloud_storage_bucket_events')}
          </button>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'versioning' })} onClick={() => setTab('versioning')}>
            {translate('plugin_cloud_storage_bucket_versioning')}
          </button>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'encryption' })} onClick={() => setTab('encryption')}>
            {translate('plugin_cloud_storage_bucket_encryption')}
          </button>
          <button type="button" className={s(styles, { tab: true, tabActive: tab === 'tags' })} onClick={() => setTab('tags')}>
            {translate('plugin_cloud_storage_bucket_tags')}
          </button>
        </div>
        {loading && <Loader />}
        {error && <div className={s(styles, { error: true })}>{error}</div>}
        {!loading && tab === 'policy' && (
          <>
            <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_policy_hint')}</p>
            <div className={s(styles, { templates: true })}>
              <Button type="button" variant="secondary" onClick={() => setPolicy(PRIVATE_POLICY)}>
                {translate('plugin_cloud_storage_policy_private')}
              </Button>
              <Button type="button" variant="secondary" onClick={() => setPolicy(publicReadPolicy(bucket))}>
                {translate('plugin_cloud_storage_policy_public_read')}
              </Button>
            </div>
            <textarea className={s(styles, { textarea: true })} value={policy} onChange={event => setPolicy(event.target.value)} />
          </>
        )}
        {!loading && tab === 'events' && (
          <>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_events_hint')}
              {contextLabel ? ` ${contextLabel}` : ''}
            </p>
            {apiHint && <p className={s(styles, { hint: true })}>{apiHint}</p>}
            {!eventsEnabled && context?.instance && (
              <div className={s(styles, { row: true })}>
                <span>{translate('plugin_cloud_storage_events_disabled')}</span>
                <Button type="button" variant="secondary" disabled={busy} onClick={() => void enableEvents()}>
                  {translate('plugin_cloud_storage_events_enable')}
                </Button>
              </div>
            )}
            {(hasCreated || hasRemoved) && (
              <p className={s(styles, { hint: true })}>
                {translate('plugin_cloud_storage_events_active')}:
                {hasCreated ? ` ${s3EventFor('created')}` : ''}
                {hasRemoved ? ` ${s3EventFor('removed')}` : ''}
              </p>
            )}
            {!!functions.length && (
              <div className={s(styles, { row: true })}>
                <select className={s(styles, { select: true })} value={selectedFn} onChange={event => setSelectedFn(event.target.value)}>
                  {functions.map(fn => (
                    <option key={fn.name} value={fn.name}>
                      {fn.app ? `${fn.name} · ${fn.app}` : fn.name}
                    </option>
                  ))}
                </select>
                <select className={s(styles, { select: true })} value={eventKind} onChange={event => setEventKind(event.target.value as EventKind)}>
                  <option value="created">{translate('plugin_cloud_storage_event_created')}</option>
                  <option value="removed">{translate('plugin_cloud_storage_event_removed')}</option>
                </select>
                <Button type="button" disabled={!selectedFn || busy} onClick={() => void connectFunction()}>
                  {translate('plugin_cloud_storage_connect_function')}
                </Button>
              </div>
            )}
            {!functions.length && !apiHint && (
              <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_events_no_functions')}</p>
            )}
            {wired.map(item => (
              <div key={`${item.fn}:${item.filterType}`} className={s(styles, { wired: true })}>
                <span>
                  {item.fn}
                  {item.app ? ` · ${item.app}` : ''} · {item.filterType}
                </span>
                <Button type="button" variant="secondary" disabled={busy} onClick={() => void disconnectFunction(item)}>
                  {translate('ui_delete')}
                </Button>
              </div>
            ))}
          </>
        )}
        {!loading && tab === 'versioning' && (
          <>
            <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_versioning_hint')}</p>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_versioning_status')}: {versioning}
            </p>
            <div className={s(styles, { row: true })}>
              <Button type="button" disabled={busy || versioning === 'Enabled'} onClick={() => void applyVersioning('Enabled')}>
                {translate('plugin_cloud_storage_versioning_enable')}
              </Button>
              <Button
                type="button"
                variant="secondary"
                disabled={busy || versioning === 'Off' || versioning === 'Suspended'}
                onClick={() => void applyVersioning('Suspended')}
              >
                {translate('plugin_cloud_storage_versioning_suspend')}
              </Button>
            </div>
          </>
        )}
        {!loading && tab === 'encryption' && (
          <>
            <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_encryption_hint')}</p>
            <p className={s(styles, { hint: true })}>
              {translate('plugin_cloud_storage_encryption_current')}: {encryption.algorithm || translate('plugin_cloud_storage_encryption_none')}
              {encryption.kmsKeyId ? ` · ${encryption.kmsKeyId}` : ''}
            </p>
            <div className={s(styles, { row: true })}>
              <input
                className={s(styles, { select: true })}
                placeholder={translate('plugin_cloud_storage_encryption_kms')}
                value={kmsKeyId}
                onChange={event => setKmsKeyId(event.target.value)}
              />
            </div>
            <div className={s(styles, { row: true })}>
              <Button type="button" disabled={busy} onClick={() => void applyEncryption('AES256')}>
                {translate('plugin_cloud_storage_encryption_sse_s3')}
              </Button>
              <Button type="button" variant="secondary" disabled={busy || !kmsKeyId.trim()} onClick={() => void applyEncryption('aws:kms')}>
                {translate('plugin_cloud_storage_encryption_sse_kms')}
              </Button>
              <Button type="button" variant="secondary" disabled={busy || !encryption.algorithm} onClick={() => void applyEncryption('none')}>
                {translate('plugin_cloud_storage_encryption_remove')}
              </Button>
            </div>
          </>
        )}
        {!loading && tab === 'tags' && (
          <>
            <p className={s(styles, { hint: true })}>{translate('plugin_cloud_storage_tags_hint')}</p>
            <CloudStorageTagsEditor tags={tags} onChange={setTags} disabled={busy} />
          </>
        )}
      </CommonDialogBody>
      <CommonDialogFooter>
        <Button type="button" variant="secondary" onClick={() => rejectDialog()}>
          {translate(tab === 'policy' || tab === 'tags' ? 'ui_processing_cancel' : 'ui_close')}
        </Button>
        <Fill />
        {tab === 'policy' && (
          <Button type="button" disabled={loading || busy} onClick={() => void savePolicy()}>
            {translate('ui_processing_save')}
          </Button>
        )}
        {tab === 'tags' && (
          <Button type="button" disabled={loading || busy} onClick={() => void saveTags()}>
            {translate('ui_processing_save')}
          </Button>
        )}
      </CommonDialogFooter>
    </CommonDialogWrapper>
  );
});
