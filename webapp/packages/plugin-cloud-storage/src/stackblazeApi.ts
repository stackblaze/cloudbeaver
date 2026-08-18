/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

export const KUBERO_WEBHOOK_ARN = 'arn:rustfs:sqs::kubero:webhook';

export interface StackblazeFunction {
  name: string;
  app?: string;
  ready?: boolean;
}

export interface StackblazeTrigger {
  name: string;
  type?: string;
  filter?: { type?: string; attributes?: { type?: string } };
}

export interface StackblazeEventsStatus {
  enabled: boolean;
  arn?: string;
}

function apiUrl(path: string): string {
  return new URL(path, window.location.origin).toString();
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(apiUrl(path), {
    credentials: 'include',
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const text = await response.text();
    let message = text || `HTTP ${response.status}`;
    try {
      const parsed = JSON.parse(text) as { message?: string; error?: string };
      message = parsed.message || parsed.error || message;
    } catch {
      /* keep raw body */
    }
    if (response.status === 401) {
      throw new Error(
        'Sign in to the dashboard to connect functions. Bucket notifications can still be saved from this session.',
      );
    }
    throw new Error(message);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function filterTypeOf(trigger: StackblazeTrigger): string {
  return trigger.filter?.type || trigger.filter?.attributes?.type || '';
}

export async function listFunctions(pipeline: string, phase: string): Promise<StackblazeFunction[]> {
  const result = await request<StackblazeFunction[]>(`/api/functions/${encodeURIComponent(pipeline)}/${encodeURIComponent(phase)}`);
  return Array.isArray(result) ? result : [];
}

export async function listTriggers(pipeline: string, phase: string, name: string): Promise<StackblazeTrigger[]> {
  const result = await request<StackblazeTrigger[]>(
    `/api/functions/${encodeURIComponent(pipeline)}/${encodeURIComponent(phase)}/${encodeURIComponent(name)}/triggers`,
  );
  return Array.isArray(result) ? result : [];
}

export async function addEventTrigger(pipeline: string, phase: string, name: string, filterType: string): Promise<void> {
  await request(`/api/functions/${encodeURIComponent(pipeline)}/${encodeURIComponent(phase)}/${encodeURIComponent(name)}/triggers`, {
    method: 'POST',
    body: JSON.stringify({ type: 'event', filterType }),
  });
}

export async function deleteEventTrigger(pipeline: string, phase: string, name: string, triggerName: string): Promise<void> {
  await request(
    `/api/functions/${encodeURIComponent(pipeline)}/${encodeURIComponent(phase)}/${encodeURIComponent(name)}/triggers/event/${encodeURIComponent(triggerName)}`,
    { method: 'DELETE' },
  );
}

export async function getRustfsEventsStatus(pipeline: string, phase: string, instance: string): Promise<StackblazeEventsStatus> {
  return request(`/api/events/sources/rustfs/${encodeURIComponent(pipeline)}/${encodeURIComponent(phase)}/${encodeURIComponent(instance)}`);
}

export async function enableRustfsEvents(pipeline: string, phase: string, instance: string): Promise<StackblazeEventsStatus> {
  return request(
    `/api/events/sources/rustfs/${encodeURIComponent(pipeline)}/${encodeURIComponent(phase)}/${encodeURIComponent(instance)}/enable`,
    { method: 'POST' },
  );
}
