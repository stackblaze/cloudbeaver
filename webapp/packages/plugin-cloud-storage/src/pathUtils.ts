/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

/** Parent node URI, so the tree can be refreshed where the deleted entry was. */
export function getParentNodeUri(uri: string): string | null {
  const index = uri.lastIndexOf('/');
  if (index <= 0) {
    return null;
  }
  const parent = uri.slice(0, index);
  return parent.endsWith('//') ? null : parent;
}

export function getPathName(path: string): string {
  const segment = path.split('/').filter(Boolean).pop() ?? path;
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

export function formatFileSize(bytes: number): string {
  if (!bytes) {
    return '—';
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size < 10 && unit > 0 ? size.toFixed(1) : Math.round(size)} ${units[unit]}`;
}
