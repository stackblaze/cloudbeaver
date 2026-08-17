/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { Bootstrap, ModuleRegistry } from '@cloudbeaver/core-di';
import { CloudStorageBootstrap } from './CloudStorageBootstrap.js';
import { CloudStorageDuckDbService } from './CloudStorageDuckDbService.js';
import { CloudStorageFileService } from './CloudStorageFileService.js';
import { CloudStorageService } from './CloudStorageService.js';
import { LocaleService } from './LocaleService.js';

export default ModuleRegistry.add({
  name: '@cloudbeaver/plugin-cloud-storage',

  configure: serviceCollection => {
    serviceCollection
      .addSingleton(Bootstrap, LocaleService)
      .addSingleton(Bootstrap, CloudStorageBootstrap)
      .addSingleton(CloudStorageService)
      .addSingleton(CloudStorageDuckDbService)
      .addSingleton(CloudStorageFileService);
  },
});
