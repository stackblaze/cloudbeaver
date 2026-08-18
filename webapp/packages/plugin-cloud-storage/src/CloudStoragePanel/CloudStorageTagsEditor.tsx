/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { Button, s, useS, useTranslate } from '@cloudbeaver/core-blocks';

import type { IFSTag } from '../queries/queries.js';
import style from './CloudStorageBucketDialog.module.css';

interface Props {
  tags: IFSTag[];
  onChange: (tags: IFSTag[]) => void;
  disabled?: boolean;
}

export function CloudStorageTagsEditor({ tags, onChange, disabled }: Props) {
  const styles = useS(style);
  const translate = useTranslate();

  function update(index: number, patch: Partial<IFSTag>) {
    onChange(tags.map((tag, i) => (i === index ? { ...tag, ...patch } : tag)));
  }

  return (
    <div>
      {tags.map((tag, index) => (
        <div key={index} className={s(styles, { row: true })}>
          <input
            className={s(styles, { select: true })}
            placeholder={translate('plugin_cloud_storage_tag_key')}
            value={tag.key}
            disabled={disabled}
            onChange={event => update(index, { key: event.target.value })}
          />
          <input
            className={s(styles, { select: true })}
            placeholder={translate('plugin_cloud_storage_tag_value')}
            value={tag.value}
            disabled={disabled}
            onChange={event => update(index, { value: event.target.value })}
          />
          <Button type="button" variant="secondary" disabled={disabled} onClick={() => onChange(tags.filter((_, i) => i !== index))}>
            {translate('ui_delete')}
          </Button>
        </div>
      ))}
      <Button type="button" variant="secondary" disabled={disabled || tags.length >= 10} onClick={() => onChange([...tags, { key: '', value: '' }])}>
        {translate('plugin_cloud_storage_tag_add')}
      </Button>
    </div>
  );
}
