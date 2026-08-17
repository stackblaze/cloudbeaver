/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */
import {
  ConnectionInfoAuthPropertiesResource,
  ConnectionInfoCustomOptionsResource,
  ConnectionInfoPropertiesResource,
  ConnectionInfoResource,
  type Connection,
  createConnectionParam,
  type IConnectionInfoParams,
} from '@cloudbeaver/core-connections';
import { injectable } from '@cloudbeaver/core-di';
import { NotificationService } from '@cloudbeaver/core-events';
import { getObjectPropertyValue } from '@cloudbeaver/core-sdk';
import { MemorySqlDataSource } from '@cloudbeaver/plugin-sql-editor';
import { SqlEditorNavigatorService } from '@cloudbeaver/plugin-sql-editor-navigation-tab';

const DUCKDB_DRIVER_ID = 'duckdb:duckdb_jdbc';
const DATA_FILE_EXTENSIONS = new Set(['csv', 'txt', 'tsv', 'tcv', 'json', 'parquet']);

interface IDuckDbConnectionConfig {
  name?: string;
  driverId?: string;
  databaseName?: string;
  saveCredentials?: boolean;
  properties?: Record<string, string>;
}

@injectable(() => [
  ConnectionInfoResource,
  ConnectionInfoCustomOptionsResource,
  ConnectionInfoAuthPropertiesResource,
  ConnectionInfoPropertiesResource,
  SqlEditorNavigatorService,
  NotificationService,
])
export class CloudStorageDuckDbService {
  private readonly duckdbConnections = new Map<string, IConnectionInfoParams>();

  constructor(
    private readonly connectionInfoResource: ConnectionInfoResource,
    private readonly connectionInfoCustomOptionsResource: ConnectionInfoCustomOptionsResource,
    private readonly connectionInfoAuthPropertiesResource: ConnectionInfoAuthPropertiesResource,
    private readonly connectionInfoPropertiesResource: ConnectionInfoPropertiesResource,
    private readonly sqlEditorNavigatorService: SqlEditorNavigatorService,
    private readonly notificationService: NotificationService,
  ) {}

  isDataFile(fileName: string): boolean {
    const extension = getFileExtension(fileName);
    return DATA_FILE_EXTENSIONS.has(extension) || extension.length > 0;
  }

  async openDataFile(s3Uri: string, s3Connection: Connection, extension: string): Promise<void> {
    try {
      const connectionKey = await this.getOrCreateDuckdbConnection(s3Connection);
      const readFn = getReadFunction(extension);
      const autoDetect = readFn === 'read_csv' && !['csv', 'tsv', 'tcv'].includes(getFileExtension(extension)) ? ', auto_detect=true' : '';
      const query = `SELECT * FROM ${readFn}('${escapeSqlLiteral(s3Uri)}'${autoDetect}) LIMIT 100`;

      await this.sqlEditorNavigatorService.openNewEditor({
        dataSourceKey: MemorySqlDataSource.key,
        name: getEditorName(s3Uri),
        connectionKey,
        query,
      });
    } catch (exception: any) {
      this.notificationService.logException(exception, 'Cloud Storage', 'Failed to open data file');
    }
  }

  private async getOrCreateDuckdbConnection(s3Connection: Connection): Promise<IConnectionInfoParams> {
    const existing = this.duckdbConnections.get(s3Connection.id);
    if (existing && this.connectionInfoResource.has(existing)) {
      return existing;
    }

    const connectionKey = createConnectionParam(s3Connection);
    await Promise.all([
      this.connectionInfoCustomOptionsResource.load(connectionKey),
      this.connectionInfoAuthPropertiesResource.load(connectionKey),
      this.connectionInfoPropertiesResource.load(connectionKey),
    ]);

    const options = this.connectionInfoCustomOptionsResource.get(connectionKey);
    const auth = this.connectionInfoAuthPropertiesResource.get(connectionKey);
    const props = this.connectionInfoPropertiesResource.get(connectionKey);

    const host = options?.host ?? '';
    const port = options?.port ?? '';
    const providerProps = (props?.properties ?? {}) as Record<string, unknown>;
    const useSsl = providerProps['useSsl'] === true || providerProps['useSsl'] === 'true';
    const pathStyle = providerProps['pathStyle'] !== false && providerProps['pathStyle'] !== 'false';

    const credentials: Record<string, string> = {};
    for (const property of auth?.authProperties ?? []) {
      if (property.id) {
        credentials[property.id] = String(getObjectPropertyValue(property) ?? '');
      }
    }

    const accessKey = credentials['userName'] ?? credentials['user'] ?? '';
    const secretKey = credentials['userPassword'] ?? credentials['password'] ?? '';
    const initSql = buildDuckDbInitSql(host, port, accessKey, secretKey, useSsl, pathStyle);

    const config: IDuckDbConnectionConfig = {
      name: `Cloud Storage DuckDB (${s3Connection.name})`,
      driverId: DUCKDB_DRIVER_ID,
      databaseName: ':memory:',
      saveCredentials: false,
      properties: {
        'init-queries': initSql,
      },
    };

    const duckdbConnection = await this.connectionInfoResource.create(s3Connection.projectId, config as any);
    const duckdbKey = createConnectionParam(duckdbConnection);
    this.duckdbConnections.set(s3Connection.id, duckdbKey);

    return duckdbKey;
  }
}

export function getFileExtension(fileName: string): string {
  const dotIndex = fileName.lastIndexOf('.');
  if (dotIndex <= 0) {
    return '';
  }

  return fileName.slice(dotIndex + 1).toLowerCase();
}

export function getReadFunction(extension: string): string {
  const ext = getFileExtension(extension) || extension.toLowerCase().replace(/^\./, '');

  switch (ext) {
    case 'json':
      return 'read_json';
    case 'parquet':
      return 'read_parquet';
    case 'csv':
    case 'txt':
    case 'tsv':
    case 'tcv':
      return 'read_csv';
    default:
      return 'read_csv';
  }
}

function buildDuckDbInitSql(
  host: string,
  port: string,
  accessKey: string,
  secretKey: string,
  useSsl: boolean,
  pathStyle: boolean,
): string {
  const endpoint = port ? `${host}:${port}` : host;

  return [
    'INSTALL httpfs;',
    'LOAD httpfs;',
    `SET s3_endpoint='${escapeSqlLiteral(endpoint)}';`,
    `SET s3_access_key_id='${escapeSqlLiteral(accessKey)}';`,
    `SET s3_secret_access_key='${escapeSqlLiteral(secretKey)}';`,
    `SET s3_use_ssl=${useSsl ? 'true' : 'false'};`,
    `SET s3_url_style='${pathStyle ? 'path' : 'vhost'}';`,
  ].join('\n');
}

function escapeSqlLiteral(value: string): string {
  return value.replace(/'/g, "''");
}

function getEditorName(s3Uri: string): string {
  const parts = s3Uri.split('/');
  return parts[parts.length - 1] || 'Data file';
}
