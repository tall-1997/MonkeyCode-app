export interface SessionUpdate {
  sessionUpdate?: string | { type?: string };
  type?: string;
  [key: string]: unknown;
}

export type LocalAgentMessageKind = 'cmd' | 'assistant' | 'thought' | 'tool' | 'toolupd' | 'plan' | 'err' | 'sys' | 'approval' | 'subagent';

export interface LocalAgentMessage {
  kind: LocalAgentMessageKind;
  text: string;
  permId?: string;
  toolName?: string;
  toolArgs?: string;
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

export function appendEngineFrame(messages: LocalAgentMessage[], frame: { type: string; kind?: string; data?: any }): LocalAgentMessage[] {
  const data = frame.data;
  if (frame.type === 'user-input') return [...messages, { kind: 'cmd', text: decodeFrameContent(data?.content) }];
  if (frame.type === 'task-started') return [...messages, { kind: 'sys', text: '任务开始执行' }];
  if (frame.type === 'task-ended') {
    const status = data?.status || 'finished';
    const label = status === 'cancelled' ? '已取消' : status === 'error' ? '异常终止' : '完成';
    return [...messages, { kind: status === 'error' ? 'err' : 'sys', text: `任务${label}（${data?.turns ?? 0} 轮）` }];
  }
  if (frame.type === 'task-error') return [...messages, { kind: 'err', text: data?.error || data?.message || '任务执行异常' }];
  if (frame.type === 'permission-req') {
    const toolArgs = data?.arguments
      ? formatFrameValue(data.arguments)
      : formatFrameValue(data?.params);
    const toolName = data?.tool || data?.tool_name || '';
    return [...messages, { kind: 'approval', text: `请求权限: ${toolName}`, permId: data?.permissionId || data?.id || '', toolName, toolArgs }];
  }
  if (frame.type === 'permission-resolved') {
    return [...messages, { kind: 'sys', text: `权限${data?.outcome === 'approved' ? '已批准' : '已拒绝'}` }];
  }
  if (frame.kind !== 'acp_event') return messages;

  const update = sessionUpdateOf(data);
  const type = sessionUpdateType(update);
  if (!update) return messages;
  if (type === 'agent_message_chunk') return appendChunk(messages, 'assistant', frameText(update.content));
  if (type === 'agent_thought_chunk') return appendChunk(messages, 'thought', frameText(update.content));
  if (type === 'tool_call') return [...messages, { kind: 'tool', text: `${update.title || update.name || ''} ${formatFrameValue(update.rawInput ?? update.arguments)}`.trim() }];
  if (type === 'tool_call_update') {
    const status = update.status || 'completed';
    const name = update.title || update.name || update.toolCallId || '工具';
    const detail = truncate(frameText(update.rawOutput ?? update.error ?? update.result));
    const text = status === 'failed' ? `${name} 失败: ${detail}` : status === 'progress' || status === 'in_progress' ? `${name} 进行中...` : `${name} 完成: ${detail}`;
    return [...messages, { kind: 'toolupd', text }];
  }
  if (type === 'plan') return [...messages, { kind: 'plan', text: frameText(update.content ?? update.plan) || '任务规划已生成' }];
  if (['subagent_tool', 'subagent_text', 'subagent_output', 'child_session'].includes(type)) {
    const name = update.agent_name || update.name || update.childSessionId || '子代理';
    return [...messages, { kind: 'subagent', text: `[${name}] ${frameText(update.content ?? update.text ?? update.output)}` }];
  }
  if (type === 'task_notification') return [...messages, { kind: 'subagent', text: `子代理完成: ${frameText(update.message ?? update.content)}` }];
  if (type === 'usage_update') return [...messages, { kind: 'sys', text: `Token 用量: ${update.prompt_tokens ?? update.inputTokens ?? 0}+${update.completion_tokens ?? update.outputTokens ?? 0}` }];
  if (type === 'compact_status') return [...messages, { kind: 'sys', text: frameText(update.message) || (update.status === 'started' ? '正在压缩上下文...' : '上下文已压缩') }];
  return messages;
}

function appendChunk(messages: LocalAgentMessage[], kind: 'assistant' | 'thought', chunk: string): LocalAgentMessage[] {
  const last = messages[messages.length - 1];
  return last?.kind === kind
    ? [...messages.slice(0, -1), { ...last, text: last.text + chunk }]
    : [...messages, { kind, text: chunk }];
}

function decodeFrameContent(value: unknown): string {
  if (typeof value !== 'string') return frameText(value);
  try {
    if (typeof atob !== 'function') return value;
    return decodeURIComponent(escape(atob(value)));
  } catch {
    return value;
  }
}

function truncate(value: string, limit = 300): string {
  return value.length > limit ? `${value.slice(0, limit)}…` : value;
}
