import { formatFrameValue, frameText, sessionUpdateOf, sessionUpdateType } from '../frameProtocol';

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
});
