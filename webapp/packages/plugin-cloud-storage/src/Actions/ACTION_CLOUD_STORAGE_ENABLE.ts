/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { createAction } from '@cloudbeaver/core-view';

export const ACTION_CLOUD_STORAGE_ENABLE = createAction('cloud-storage-enable', {
  label: 'plugin_cloud_storage_action_enable_label',
  type: 'checkbox',
});
