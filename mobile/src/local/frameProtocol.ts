export interface SessionUpdate {
  sessionUpdate?: string | { type?: string };
  type?: string;
  [key: string]: unknown;
}

export function sessionUpdateOf(data: unknown): SessionUpdate | null {
  if (!data || typeof data !== 'object') return null;
  const envelope = data as { update?: unknown };
  const update = envelope.update ?? data;
  return update && typeof update === 'object' ? update as SessionUpdate : null;
}

export function sessionUpdateType(update: SessionUpdate | null): string {
  if (!update) return '';
  if (typeof update.sessionUpdate === 'string') return update.sessionUpdate;
  return update.sessionUpdate?.type ?? update.type ?? '';
}

export function frameText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object') {
    const content = value as { text?: unknown; content?: unknown; message?: unknown };
    if (typeof content.text === 'string') return content.text;
    if (typeof content.content === 'string') return content.content;
    if (typeof content.message === 'string') return content.message;
    return JSON.stringify(value);
  }
  return value == null ? '' : String(value);
}

export function formatFrameValue(value: unknown): string {
  return typeof value === 'string' ? value : value == null ? '' : JSON.stringify(value);
}
