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
  SnackbarBody,
  SnackbarContent,
  SnackbarFooter,
  SnackbarStatus,
  SnackbarWrapper,
  useActivationDelay,
  useErrorDetails,
  useStateDelay,
  useTranslate,
} from '@cloudbeaver/core-blocks';
import { ENotificationType, type INotificationProcessExtraProps, type NotificationComponent } from '@cloudbeaver/core-events';

export interface CloudStorageTransferSnackbarProps extends INotificationProcessExtraProps {
  onCancel?: () => void | Promise<void>;
  onResume?: () => void | Promise<void>;
}

export const CloudStorageTransferSnackbar: NotificationComponent<CloudStorageTransferSnackbarProps> = observer(
  function CloudStorageTransferSnackbar({ notification, state, onCancel, onResume }) {
    const { error, title, message, status } = state!;
    const translate = useTranslate();
    const details = useErrorDetails(error);
    const [delayState, setDelayState] = useState(false);
    const displayedReal = notification.state.deleteDelay === 0;
    const displayed = useStateDelay(delayState, 750);

    useEffect(() => {
      if (displayedReal) {
        setDelayState(true);
      }
    }, [displayedReal]);

    useActivationDelay(status === ENotificationType.Success, 3000, notification.close);

    if (!displayed) {
      return null;
    }

    return (
      <SnackbarWrapper
        closing={!!notification.state.deleteDelay}
        persistent={status === ENotificationType.Loading || status === ENotificationType.Error}
        onClose={() => notification.close(false)}
      >
        <SnackbarStatus status={status} />
        <SnackbarContent>
          <SnackbarBody title={translate(title)}>{message && translate(message)}</SnackbarBody>
          <SnackbarFooter timestamp={notification.timestamp}>
            {details.hasDetails && (
              <Button type="button" variant="secondary" disabled={details.isOpen} onClick={details.open}>
                {translate('ui_errors_details')}
              </Button>
            )}
            {onCancel && status === ENotificationType.Loading && (
              <Button onClick={onCancel}>{translate('ui_processing_cancel')}</Button>
            )}
            {onResume && status === ENotificationType.Error && (
              <>
                <Button type="button" variant="secondary" onClick={() => notification.close(false)}>
                  {translate('plugin_cloud_storage_dismiss')}
                </Button>
                <Button
                  onClick={() => {
                    notification.close(false);
                    void onResume();
                  }}
                >
                  {translate('plugin_cloud_storage_resume')}
                </Button>
              </>
            )}
          </SnackbarFooter>
        </SnackbarContent>
      </SnackbarWrapper>
    );
  },
);
