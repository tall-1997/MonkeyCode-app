import { appendEngineFrame, formatFrameValue, frameText, sessionUpdateOf, sessionUpdateType } from '../frameProtocol';

describe('local frame protocol', () => {
  it('reads the desktop-compatible update envelope', () => {
    const update = sessionUpdateOf({
      update: {
        sessionUpdate: 'agent_message_chunk',
        content: { type: 'text', text: 'hello' },
      },
    });

    expect(sessionUpdateType(update)).toBe('agent_message_chunk');
    expect(frameText(update?.content)).toBe('hello');
  });

  it('keeps compatibility with legacy flat frames', () => {
    const update = sessionUpdateOf({ type: 'tool_call', arguments: { path: '/workspace' } });

    expect(sessionUpdateType(update)).toBe('tool_call');
    expect(formatFrameValue(update?.arguments)).toBe('{"path":"/workspace"}');
  });

  it('supports nested session update types', () => {
    expect(sessionUpdateType({ sessionUpdate: { type: 'usage_update' } })).toBe('usage_update');
  });

  it('replays user input and joins assistant chunks', () => {
    const encoded = Buffer.from('检查项目', 'utf8').toString('base64');
    let messages = appendEngineFrame([], { type: 'user-input', data: { content: encoded } });
    messages = appendEngineFrame(messages, { type: 'task-running', kind: 'acp_event', data: { update: { sessionUpdate: 'agent_message_chunk', content: { text: '完成' } } } });
    messages = appendEngineFrame(messages, { type: 'task-running', kind: 'acp_event', data: { update: { sessionUpdate: 'agent_message_chunk', content: { text: '检查' } } } });

    expect(messages).toEqual([
      { kind: 'cmd', text: '检查项目' },
      { kind: 'assistant', text: '完成检查' },
    ]);
  });
});
