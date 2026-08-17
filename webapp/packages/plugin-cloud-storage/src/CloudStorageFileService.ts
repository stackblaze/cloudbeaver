/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import { injectable } from '@cloudbeaver/core-di';
import { NotificationService } from '@cloudbeaver/core-events';
import { MemorySqlDataSource } from '@cloudbeaver/plugin-sql-editor';
import { SqlEditorNavigatorService } from '@cloudbeaver/plugin-sql-editor-navigation-tab';

import { CloudStorageService } from './CloudStorageService.js';

const SQL_EXTENSIONS = new Set(['sql']);

@injectable(() => [CloudStorageService, SqlEditorNavigatorService, NotificationService])
export class CloudStorageFileService {
  constructor(
    private readonly cloudStorageService: CloudStorageService,
    private readonly sqlEditorNavigatorService: SqlEditorNavigatorService,
    private readonly notificationService: NotificationService,
  ) {}

  isSqlFile(fileName: string): boolean {
    const dotIndex = fileName.lastIndexOf('.');
    if (dotIndex <= 0) {
      return false;
    }

    return SQL_EXTENSIONS.has(fileName.slice(dotIndex + 1).toLowerCase());
  }

  async openSqlFile(nodePath: string): Promise<void> {
    try {
      const content = await this.cloudStorageService.readFileContent(nodePath);
      const name = nodePath.split('/').pop() ?? 'script.sql';

      await this.sqlEditorNavigatorService.openNewEditor({
        dataSourceKey: MemorySqlDataSource.key,
        name,
        query: content,
      });
    } catch (exception: any) {
      this.notificationService.logException(exception, 'Cloud Storage', 'Failed to open SQL file');
    }
  }
}
