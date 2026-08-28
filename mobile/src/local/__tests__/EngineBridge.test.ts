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
  approvePermission: jest.fn(),
  denyPermission: jest.fn(),
};
const nativeListeners: Record<string, (event: any) => void> = {};

jest.doMock('react-native', () => ({
  Platform: { OS: 'android' },
  NativeModules: { PrivilegedExecution: mockPrivileged },
  DeviceEventEmitter: {
    addListener: jest.fn((event: string, listener: (value: any) => void) => {
      nativeListeners[event] = listener;
      return { remove: jest.fn() };
    }),
  },
}));

jest.doMock('@/local/PermissionDetector', () => ({
  permissionDetector: { isPrivileged: () => true, getState: () => null },
}));

jest.doMock('expo-file-system', () => ({
  __esModule: true,
  default: {
    getInfoAsync: jest.fn().mockResolvedValue({ exists: false, isDirectory: false, size: 0 }),
    readAsStringAsync: jest.fn().mockResolvedValue(''),
    writeAsStringAsync: jest.fn(),
    makeDirectoryAsync: jest.fn(),
    deleteAsync: jest.fn(),
    moveAsync: jest.fn(),
    readDirectoryAsync: jest.fn().mockResolvedValue([]),
  },
  getInfoAsync: jest.fn().mockResolvedValue({ exists: false, isDirectory: false, size: 0 }),
  readAsStringAsync: jest.fn().mockResolvedValue(''),
  writeAsStringAsync: jest.fn(),
  makeDirectoryAsync: jest.fn(),
  deleteAsync: jest.fn(),
  moveAsync: jest.fn(),
  readDirectoryAsync: jest.fn().mockResolvedValue([]),
  Paths: {
    document: { uri: 'file:///test-documents/' },
    cache: { uri: 'file:///test-cache/' },
  },
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

  test('权限审批全链透传相同 permissionId', async () => {
    mockPrivileged.startAgent.mockResolvedValue('agent_1');
    mockPrivileged.approvePermission.mockResolvedValue(true);
    mockPrivileged.denyPermission.mockResolvedValue(true);
    await bridge.startEngine(config as never);
    await bridge.approvePermission('perm_agent_1_call_7', true);
    await bridge.denyPermission('perm_agent_1_call_8');
    expect(mockPrivileged.approvePermission).toHaveBeenCalledWith('perm_agent_1_call_7', true);
    expect(mockPrivileged.denyPermission).toHaveBeenCalledWith('perm_agent_1_call_8');
  });

  test('Frame 保留 data.update.sessionUpdate 协议对象', () => {
    const frames: any[] = [];
    bridge.onFrame((frame: any) => frames.push(frame));
    nativeListeners.engineFrame({
      type: 'task-running', kind: 'acp_event', timestamp: 1, seq: 2,
      data: { update: { sessionUpdate: 'tool_call', toolCallId: 'call_1' } },
    });
    expect(frames[0].data.update).toEqual({ sessionUpdate: 'tool_call', toolCallId: 'call_1' });
  });

  test('engineStatus 对象同时更新状态与详情 DTO', () => {
    nativeListeners.engineStatus({ status: 'crashed', phase: 'subagent_error', detail: 'boom' });
    expect(bridge.getStatus()).toBe('crashed');
    expect(bridge.getStatusDetail()).toMatchObject({ phase: 'subagent_error', detail: 'boom' });
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
