import { NativeModules, DeviceEventEmitter } from 'react-native';
import { permissionDetector, ShellResult } from './PermissionDetector';

const { PrivilegedExecution } = NativeModules;

interface TerminalSession {
  id: string;
  workDir: string;
  identity: 'user' | 'root';
  environment: 'android' | 'linux' | 'ubuntu';
}

class TerminalBridge {
  private sessions: Map<string, TerminalSession> = new Map();
  private dataListeners: Map<string, Array<(data: string) => void>> = new Map();
  private exitListeners: Map<string, Array<(exitCode: number) => void>> = new Map();
  private _dataSub: { remove: () => void } | null = null;
  private _exitSub: { remove: () => void } | null = null;

  constructor() {
    // native 端用 RCTDeviceEventEmitter 全局发射，JS 端用全局 DeviceEventEmitter 接收；
    // 不要 new NativeEventEmitter(legacy NativeModule) —— TurboModule 新架构要求 addListener，
    // legacy 模块不支持 → 构造抛异常导致闪退。
    if (PrivilegedExecution) {
      this._dataSub = DeviceEventEmitter.addListener('shellData', (event: { sessionId: string; data: string }) => {
        const listeners = this.dataListeners.get(event.sessionId);
        listeners?.forEach((l) => l(event.data));
      });
      this._exitSub = DeviceEventEmitter.addListener('shellExit', (event: { sessionId: string; exitCode: number }) => {
        const listeners = this.exitListeners.get(event.sessionId);
        listeners?.forEach((l) => l(event.exitCode));
        this.sessions.delete(event.sessionId);
      });
    }
  }

  isAvailable(): boolean {
    return permissionDetector.isPrivileged() && !!PrivilegedExecution;
  }

  async createSession(
    workDir: string,
    identity: 'user' | 'root' = 'user',
    environment: 'android' | 'linux' | 'ubuntu' = 'android'
  ): Promise<string> {
    if (!this.isAvailable()) {
      throw new Error('Terminal is only available in privileged mode');
    }

    const sessionId = await PrivilegedExecution.createShellSession(workDir, identity);
    this.sessions.set(sessionId, { id: sessionId, workDir, identity, environment });
    return sessionId;
  }

  async write(sessionId: string, data: string): Promise<void> {
    if (!this.sessions.has(sessionId)) {
      throw new Error(`Session not found: ${sessionId}`);
    }
    await PrivilegedExecution.writeToSession(sessionId, data);
  }

  async execCommand(
    command: string,
    identity: 'user' | 'root' = 'user',
    environment: 'android' | 'linux' | 'ubuntu' = 'android'
  ): Promise<ShellResult> {
    if (!this.isAvailable()) {
      throw new Error('Terminal is only available in privileged mode');
    }

    if (environment === 'ubuntu' || environment === 'linux') {
      return PrivilegedExecution.execSandboxCommand(command);
    }
    return PrivilegedExecution.execCommand(command, identity);
  }

  async destroySession(sessionId: string): Promise<void> {
    if (!this.sessions.has(sessionId)) return;
    await PrivilegedExecution.destroySession(sessionId);
    this.sessions.delete(sessionId);
    this.dataListeners.delete(sessionId);
    this.exitListeners.delete(sessionId);
  }

  onData(sessionId: string, callback: (data: string) => void): () => void {
    if (!this.dataListeners.has(sessionId)) {
      this.dataListeners.set(sessionId, []);
    }
    this.dataListeners.get(sessionId)!.push(callback);
    return () => {
      const listeners = this.dataListeners.get(sessionId);
      if (listeners) {
        this.dataListeners.set(
          sessionId,
          listeners.filter((l) => l !== callback)
        );
      }
    };
  }

  onExit(sessionId: string, callback: (exitCode: number) => void): () => void {
    if (!this.exitListeners.has(sessionId)) {
      this.exitListeners.set(sessionId, []);
    }
    this.exitListeners.get(sessionId)!.push(callback);
    return () => {
      const listeners = this.exitListeners.get(sessionId);
      if (listeners) {
        this.exitListeners.set(
          sessionId,
          listeners.filter((l) => l !== callback)
        );
      }
    };
  }

  getSessions(): TerminalSession[] {
    return Array.from(this.sessions.values());
  }
}

export const terminalBridge = new TerminalBridge();
export default TerminalBridge;
