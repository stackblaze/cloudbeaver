/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

export const MULTIPART_THRESHOLD = 8 * 1024 * 1024;
export const MULTIPART_MAX_PARTS = 10_000;
export const MULTIPART_PART_CONCURRENCY = 3;

export interface ICloudStorageUploadItem {
  file: File;
  /** Posix path relative to the drop/picker root, including the file name. */
  relativePath: string;
}

export interface ICloudStorageMultipartPart {
  partNumber: number;
  etag: string;
}

export interface ICloudStorageMultipartState {
  uploadId: string;
  destParent: string;
  partSize: number;
  parts: ICloudStorageMultipartPart[];
}

export function partSizeFor(fileSize: number): number {
  const needed = Math.ceil(fileSize / MULTIPART_MAX_PARTS);
  return Math.max(MULTIPART_THRESHOLD, needed);
}

export function partCountFor(fileSize: number, partSize: number): number {
  return Math.max(1, Math.ceil(fileSize / partSize));
}

interface FileSystemEntryLike {
  isFile: boolean;
  isDirectory: boolean;
  name: string;
}

interface FileSystemFileEntryLike extends FileSystemEntryLike {
  file(successCallback: (file: File) => void, errorCallback?: (error: Error) => void): void;
}

interface FileSystemDirectoryReaderLike {
  readEntries(successCallback: (entries: FileSystemEntryLike[]) => void, errorCallback?: (error: Error) => void): void;
}

interface FileSystemDirectoryEntryLike extends FileSystemEntryLike {
  createReader(): FileSystemDirectoryReaderLike;
}

export function sanitizeRelativePath(path: string): string | null {
  const parts = path
    .replace(/\\/g, '/')
    .split('/')
    .filter(part => part && part !== '.');
  if (!parts.length || parts.some(part => part === '..')) {
    return null;
  }
  return parts.join('/');
}

export function uploadItemsFromFiles(files: File[]): ICloudStorageUploadItem[] {
  const items: ICloudStorageUploadItem[] = [];
  for (const file of files) {
    const raw = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
    const relativePath = sanitizeRelativePath(raw);
    if (relativePath) {
      items.push({ file, relativePath });
    }
  }
  return items;
}

export async function uploadItemsFromDataTransfer(dataTransfer: DataTransfer): Promise<ICloudStorageUploadItem[]> {
  const entries: FileSystemEntryLike[] = [];
  for (const item of Array.from(dataTransfer.items)) {
    const entry = (item as DataTransferItem & { webkitGetAsEntry?: () => FileSystemEntryLike | null }).webkitGetAsEntry?.();
    if (entry) {
      entries.push(entry);
    }
  }

  if (!entries.length) {
    return uploadItemsFromFiles(Array.from(dataTransfer.files));
  }

  const items: ICloudStorageUploadItem[] = [];
  for (const entry of entries) {
    await walkEntry(entry, '', items);
  }
  return items;
}

export function pickLocalFiles(folder = false): Promise<File[]> {
  return new Promise(resolve => {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    if (folder) {
      input.setAttribute('webkitdirectory', '');
      input.setAttribute('directory', '');
      (input as HTMLInputElement & { webkitdirectory?: boolean }).webkitdirectory = true;
    }
    input.onchange = () => resolve(Array.from(input.files ?? []));
    input.click();
  });
}

async function walkEntry(entry: FileSystemEntryLike, prefix: string, items: ICloudStorageUploadItem[]): Promise<void> {
  if (entry.isFile) {
    const file = await readFileEntry(entry as FileSystemFileEntryLike);
    const relativePath = sanitizeRelativePath(prefix ? `${prefix}/${file.name}` : file.name);
    if (relativePath) {
      items.push({ file, relativePath });
    }
    return;
  }

  if (entry.isDirectory) {
    const nextPrefix = prefix ? `${prefix}/${entry.name}` : entry.name;
    const children = await readAllEntries(entry as FileSystemDirectoryEntryLike);
    for (const child of children) {
      await walkEntry(child, nextPrefix, items);
    }
  }
}

function readFileEntry(entry: FileSystemFileEntryLike): Promise<File> {
  return new Promise((resolve, reject) => {
    entry.file(resolve, reject);
  });
}

/** Chrome returns at most 100 entries per readEntries call. */
function readAllEntries(dir: FileSystemDirectoryEntryLike): Promise<FileSystemEntryLike[]> {
  const reader = dir.createReader();
  const all: FileSystemEntryLike[] = [];

  return new Promise((resolve, reject) => {
    const read = () => {
      reader.readEntries(batch => {
        if (!batch.length) {
          resolve(all);
          return;
        }
        all.push(...batch);
        read();
      }, reject);
    };
    read();
  });
}
