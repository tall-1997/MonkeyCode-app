/**
 * EngineBridge 状态机单元测试 —— 覆盖 engine 生命周期与错误重试逻辑。
 * 用 jest.doMock + require 保证 react-native mock 在 EngineBridge 顶层解构 NativeModules 前生效。
 */
const mockPrivileged = {
  startAgent: jest.fn(),
  stopAgent: jest.fn(),
  cancelAgent: jest.fn(),
};

jest.doMock('react-native', () => ({
  Platform: { OS: 'android' },
  NativeModules: { PrivilegedExecution: mockPrivileged },
  NativeEventEmitter: jest.fn().mockImplementation(() => ({
    addListener: jest.fn(),
  })),
}));

jest.doMock('@/local/PermissionDetector', () => ({
  permissionDetector: { isPrivileged: () => true, getState: () => null },
}));

// eslint-disable-next-line @typescript-eslint/no-var-requires
const EngineBridge = require('../EngineBridge').default;

const config = {
  binaryPath: '/data/local/tmp/ohmyagent',
  configDir: '/data/data/com.monkeycode/ohmyagent',
  workDir: '/sdcard/MonkeyCode',
  modelConfig: {
    type: 'openai', model: 'gpt-4o', baseUrl: 'https://api.example.com',
    apiKey: 'test-key', contextWindow: 128000, maxOutput: 32768,
    supportsImages: true, thinking: { enabled: false, effort: 'low' },
  },
};

describe('EngineBridge 引擎状态机', () => {
  let bridge: InstanceType<typeof EngineBridge>;

  beforeEach(() => {
    jest.clearAllMocks();
    bridge = new EngineBridge();
  });

  test('启动前状态为 stopped', () => {
    expect(bridge.getStatus()).toBe('stopped');
  });

  test('startEngine 成功后状态为 ready', async () => {
    mockPrivileged.startAgent.mockResolvedValue(undefined);
    await bridge.startEngine(config);
    expect(bridge.getStatus()).toBe('ready');
    expect(mockPrivileged.startAgent).toHaveBeenCalled();
  });

  test('stopEngine 后状态回到 stopped', async () => {
    mockPrivileged.stopAgent.mockResolvedValue(undefined);
    await bridge.startEngine(config);
    await bridge.stopEngine();
    expect(bridge.getStatus()).toBe('stopped');
  });

  test('引擎未就绪时 createSession 抛错', async () => {
    await expect(bridge.createSession({ description: 'x' })).rejects.toThrow('Engine not ready');
  });

  test('onStatusChange 监听器收到 starting -> ready 状态', async () => {
    const seen: string[] = [];
    bridge.onStatusChange((s) => seen.push(s));
    mockPrivileged.startAgent.mockResolvedValue(undefined);
    await bridge.startEngine(config);
    expect(seen).toContain('starting');
    expect(seen).toContain('ready');
  });

  test('startEngine 失败后触发可重试错误回调并保持 starting（等待外部重试）', async () => {
    const errors: Array<{ code: string; recoverable: boolean }> = [];
    bridge.onError((e) => errors.push(e));
    mockPrivileged.startAgent.mockRejectedValue(new Error('boom'));
    await bridge.startEngine(config); // 内部含真实 1s 延迟
    expect(errors.length).toBeGreaterThan(0);
    expect(errors[0].recoverable).toBe(true);
    expect(bridge.getStatus()).toBe('starting'); // 单次调用失败不置 failed，等待外部重试
  }, 15000);

  test('sendInput 在无会话时抛错', async () => {
    await expect(bridge.sendInput('hello')).rejects.toThrow('No active session');
  });
});