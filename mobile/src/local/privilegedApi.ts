import { NativeModules, Platform } from 'react-native';
import { permissionDetector, ShellResult } from './PermissionDetector';

const { PrivilegedExecution } = NativeModules;

export interface GovernorConfig {
  deviceToolsEnabled: boolean;
  sensitiveDataEnabled: boolean;
  sensitiveOpsEnabled: boolean;
  terminalEnabled: boolean;
  terminalIdentity: 'user' | 'root';
  guiAgentEnabled: boolean;
  systemHookEnabled: boolean;
  browserEnabled: boolean;
  mcpServerEnabled: boolean;
  telemetryEnabled: boolean;
}

const DEFAULTS: GovernorConfig = {
  deviceToolsEnabled: true,
  sensitiveDataEnabled: true,
  sensitiveOpsEnabled: true,
  terminalEnabled: true,
  terminalIdentity: 'user',
  guiAgentEnabled: true,
  systemHookEnabled: false,
  browserEnabled: true,
  mcpServerEnabled: false,
  telemetryEnabled: false,
};

const KEY = 'mc.governorConfig';

/** 读取/写入每条敏感能力开关（落地 AsyncStorage），UI 设置页消费。 */
export async function loadGovernorConfig(): Promise<GovernorConfig> {
  try {
    if (Platform.OS !== 'android') return { ...DEFAULTS };
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : { ...DEFAULTS };
  } catch {
    return { ...DEFAULTS };
  }
}

export async function saveGovernorConfig(cfg: GovernorConfig): Promise<void> {
  if (Platform.OS !== 'android') return;
  const { AsyncStorage } = require('@react-native-async-storage/async-storage');
  await AsyncStorage.setItem(KEY, JSON.stringify(cfg));
}

/** 检测超能力项是否可通过特权层执行（沙箱模式下全部禁用）。 */
export function isCapable(cap: keyof GovernorConfig): boolean {
  if (!permissionDetector.isPrivileged()) return false;
  if (!PrivilegedExecution) return false;
  return true;
}

export interface DeviceStatus {
  battery: number;
  charging: boolean;
  storageAvailableMB: number;
  storageTotalMB: number;
  memoryAvailableMB: number;
  memoryTotalMB: number;
  wifiEnabled: boolean;
}

/** 特权 API 门面：所有底层调用收敛到这里，UI 不直接触碰原生模块。 */
export const privilegedApi = {
  exists: () => Platform.OS === 'android' && !!PrivilegedExecution,

  isPrivileged: () => permissionDetector.isPrivileged(),

  execCommand: (command: string, identity: 'user' | 'root' = 'user'): Promise<ShellResult> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.execCommand(command, identity);
  },

  execAlpine: (command: string): Promise<ShellResult> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.execAlpineCommand(command);
  },

  execUbuntu: (command: string): Promise<ShellResult> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.execUbuntuCommand(command);
  },

  execSandbox: (command: string): Promise<ShellResult> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.execSandboxCommand(command);
  },

  listDirectory: (path: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.listDirectory(path);
  },

  readFile: (path: string, encoding: 'utf8' | 'base64' = 'utf8') => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.readFile(path, encoding);
  },

  writeFile: (path: string, content: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.writeFile(path, content);
  },

  createDirectory: (path: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.createDirectory(path);
  },

  deleteEntry: (path: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.deleteEntry(path);
  },

  getFileInfo: (path: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.getFileInfo(path);
  },

  getDeviceStatus: (): Promise<DeviceStatus> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.getDeviceStatus();
  },

  mediaControl: (action: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.mediaControl(action);
  },

  setVolume: (stream: string, level: number) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.setVolume(stream, level);
  },

  takeScreenshot: () => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.takeScreenshot();
  },

  getAccessibilityTree: () => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.getAccessibilityTree();
  },

  performClick: (x: number, y: number) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.performClick(x, y);
  },

  performSwipe: (x1: number, y1: number, x2: number, y2: number) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.performSwipe(x1, y1, x2, y2);
  },

  performInput: (text: string) => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.performInput(text);
  },

  installAlpine: () => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    // 原生签名 installAlpineEnvironment(promise) 由 RN 自动注入 Promise；
    // 进度通过 native 事件 "alpineInstallProgress" 广播，不应传入 JS 回调。
    return PrivilegedExecution.installAlpineEnvironment();
  },

  isAlpineInstalled: () => {
    if (!PrivilegedExecution) return Promise.resolve(false);
    return PrivilegedExecution.isAlpineInstalled();
  },

  startMcpServer: (port: number = 8899): Promise<{ url: string; token: string }> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.startMcpServer(port);
  },

  stopMcpServer: (): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.resolve(false);
    return PrivilegedExecution.stopMcpServer();
  },

  browserNavigate: (url: string): Promise<string> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserNavigate(url);
  },

  browserScreenshot: (elementRef?: string): Promise<string> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserScreenshot(elementRef || '');
  },

  browserSnapshot: (): Promise<string> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserSnapshot();
  },

  browserClick: (ref: string): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserClick(ref);
  },

  browserType: (ref: string, text: string): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserType(ref, text);
  },

  browserScroll: (ref?: string): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserScroll(ref || '');
  },

  browserEvaluate: (expression: string): Promise<string> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserEvaluate(expression);
  },

  browserTabs: (action: string, tabId?: string): Promise<string> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserTabs(action, tabId || '');
  },

  browserDialog: (action: string): Promise<string> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.browserDialog(action);
  },

  isUbuntuInstalled: (): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.resolve(false);
    return PrivilegedExecution.getUbuntuStatus().then((status: { installed: boolean }) => status.installed);
  },

  installUbuntu: (): Promise<{ success: boolean }> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.installUbuntu();
  },

  getUbuntuStatus: (): Promise<{ installed: boolean; installing: boolean }> => {
    if (!PrivilegedExecution) return Promise.resolve({ installed: false, installing: false });
    return PrivilegedExecution.getUbuntuStatus();
  },

  setSandboxType: (type: 'ubuntu' | 'alpine'): Promise<void> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.setSandboxType(type);
  },

  getSandboxType: (): Promise<'ubuntu' | 'alpine'> => {
    if (!PrivilegedExecution) return Promise.resolve('alpine');
    return PrivilegedExecution.getSandboxType();
  },

  approvePermission: (permissionId: string, remember = false): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.approvePermission(permissionId, remember);
  },

  denyPermission: (permissionId: string): Promise<boolean> => {
    if (!PrivilegedExecution) return Promise.reject(new Error('特权模块不可用'));
    return PrivilegedExecution.denyPermission(permissionId);
  },
};

export default privilegedApi;
