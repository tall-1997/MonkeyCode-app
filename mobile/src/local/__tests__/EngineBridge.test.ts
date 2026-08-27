/**
 * EngineBridge 状态机单元测试（自研 Agent，无上游 ohmyagent）。
 * 用 jest.doMock + require 保证 react-native mock 在 EngineBridge 顶层解构 NativeModules 前生效。
 */
const mockPrivileged = {
  startAgent: jest.fn(),
  stopAgent: jest.fn(),
  cancelAgent: jest.fn(),
  pauseAgent: jest.fn(),
  sendAgentInput: jest.fn(),
};

jest.doMock('react-native', () => ({
  Platform: { OS: 'android' },
  NativeModules: { PrivilegedExecution: mockPrivileged },
  DeviceEventEmitter: {
    addListener: jest.fn(() => ({ remove: jest.fn() })),
  },
}));

jest.doMock('@/local/PermissionDetector', () => ({
  permissionDetector: { isPrivileged: () => true, getState: () => null },
}));

// eslint-disable-next-line @typescript-eslint/no-var-requires
const EngineBridge = require('../EngineBridge').default;

const config = {
  workDir: '/sdcard/MonkeyCode',
  modelConfig: {
    type: 'openai', model: 'gpt-4o', baseUrl: 'https://api.example.com',
    apiKey: 'test-key', contextWindow: 128000, maxOutput: 32768,
    supportsImages: true, thinking: { enabled: false, effort: 'low' },
  },
  initialInput: '你好',
};

describe('EngineBridge 引擎状态机（自研 Agent）', () => {
  let bridge: InstanceType<typeof EngineBridge>;

  beforeEach(() => {
    jest.clearAllMocks();
    bridge = new EngineBridge();
  });

  test('启动前状态为 stopped', () => {
    expect(bridge.getStatus()).toBe('stopped');
  });

  test('startEngine 成功返回会话 id 且状态 ready', async () => {
    mockPrivileged.startAgent.mockResolvedValue('agent_1');
    const sid = await bridge.startEngine(config as never);
    expect(sid).toBe('agent_1');
    expect(bridge.getStatus()).toBe('ready');
    expect(mockPrivileged.startAgent).toHaveBeenCalled();
  });

  test('stopEngine 后状态回到 stopped', async () => {
    mockPrivileged.startAgent.mockResolvedValue('agent_1');
    mockPrivileged.stopAgent.mockResolvedValue(undefined);
    await bridge.startEngine(config as never);
    await bridge.stopEngine();
    expect(bridge.getStatus()).toBe('stopped');
  });

  test('sendInput 在无会话时抛错', async () => {
    await expect(bridge.sendInput('hello')).rejects.toThrow('No active session');
  });

  test('sendInput 在会话活动时转发 sendAgentInput', async () => {
    mockPrivileged.startAgent.mockResolvedValue('agent_1');
    mockPrivileged.sendAgentInput.mockResolvedValue(undefined);
    await bridge.startEngine(config as never);
    await bridge.sendInput('继续');
    expect(mockPrivileged.sendAgentInput).toHaveBeenCalledWith('继续');
  });

  test('onStatusChange 监听器收到 starting -> ready 状态', async () => {
    const seen: string[] = [];
    bridge.onStatusChange((s) => seen.push(s));
    mockPrivileged.startAgent.mockResolvedValue('agent_1');
    await bridge.startEngine(config as never);
    expect(seen).toContain('starting');
    expect(seen).toContain('ready');
  });

  test('startEngine 失败后触发可重试错误回调并保持 starting（等待外部重试）', async () => {
    const errors: Array<{ code: string; recoverable: boolean }> = [];
    bridge.onError((e) => errors.push(e));
    mockPrivileged.startAgent.mockRejectedValue(new Error('boom'));
    await expect(bridge.startEngine(config as never)).rejects.toThrow('boom');
    expect(errors.length).toBeGreaterThan(0);
    expect(errors[0].recoverable).toBe(true);
    expect(bridge.getStatus()).toBe('starting'); // 失败后等待外部重试
  }, 15000);
});