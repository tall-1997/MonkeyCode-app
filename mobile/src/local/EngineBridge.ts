import { NativeModules, Platform, NativeEventEmitter } from 'react-native';
import { permissionDetector } from './PermissionDetector';

const { PrivilegedExecution } = NativeModules;

/** 本地引擎配置：直接对接自研 AgentRuntime（OpenAI 兼容端点），无上游 ohmyagent。 */
export interface EngineConfig {
  workDir: string;
  modelConfig: {
    type: string;
    model: string;
    baseUrl: string;
    apiKey: string;
    contextWindow: number;
    maxOutput: number;
    supportsImages: boolean;
    thinking: { enabled: boolean; effort: string };
  };
  systemPrompt?: string;
  initialInput?: string;
  skills?: string[];
  maxTurns?: number;
}

export type EngineStatus = 'stopped' | 'starting' | 'ready' | 'crashed' | 'failed';

export interface EngineFrame {
  type: string;
  kind?: string;
  data?: any;
  timestamp: number;
  seq: number;
}

class EngineBridge {
  private status: EngineStatus = 'stopped';
  private currentSessionId: string | null = null;
  private frameListeners: Array<(frame: EngineFrame) => void> = [];
  private statusListeners: Array<(status: EngineStatus) => void> = [];
  private errorListeners: Array<(error: { code: string; message: string; recoverable: boolean }) => void> = [];
  private retryCount: number = 0;
  private emitter: NativeEventEmitter | null = null;

  constructor() {
    if (Platform.OS === 'android' && PrivilegedExecution) {
      this.emitter = new NativeEventEmitter(PrivilegedExecution);
      this.emitter.addListener('engineFrame', (frame: EngineFrame) => {
        this.frameListeners.forEach((l) => l(frame));
      });
      this.emitter.addListener('engineStatus', (status: EngineStatus) => {
        this.status = status;
        this.statusListeners.forEach((l) => l(status));
      });
    }
  }

  getStatus(): EngineStatus {
    return this.status;
  }

  /** 启动本地自研 Agent。返回引擎会话 id。 */
  async startEngine(config: EngineConfig): Promise<string> {
    if (this.status === 'ready' || this.status === 'starting') {
      return this.currentSessionId || '';
    }
    this.setStatus('starting');
    this.retryCount = 0;
    try {
      if (!PrivilegedExecution) throw new Error('特权模块不可用');
      const sid = await PrivilegedExecution.startAgent(JSON.stringify(config));
      this.currentSessionId = sid;
      this.setStatus('ready');
      return sid as string;
    } catch (e: any) {
      await this.handleStartFailure(e);
      throw e;
    }
  }

  /** 发送用户输入（注入 steering 队列，下一轮模型回合生效）。 */
  async sendInput(content: string): Promise<void> {
    if (!this.currentSessionId) throw new Error('No active session');
    if (PrivilegedExecution) {
      await PrivilegedExecution.sendAgentInput(content);
      return;
    }
    throw new Error('特权模块不可用');
  }

  async stopEngine(): Promise<void> {
    if (this.status === 'stopped') return;
    try {
      if (PrivilegedExecution) await PrivilegedExecution.stopAgent();
    } catch (_) {
    } finally {
      this.setStatus('stopped');
      this.currentSessionId = null;
    }
  }

  async pauseEngine(): Promise<void> {
    if (PrivilegedExecution && this.currentSessionId) await PrivilegedExecution.pauseAgent();
  }

  async cancelTask(): Promise<void> {
    if (PrivilegedExecution && this.currentSessionId) await PrivilegedExecution.cancelAgent();
    this.currentSessionId = null;
  }

  onFrame(callback: (frame: EngineFrame) => void): () => void {
    this.frameListeners.push(callback);
    return () => {
      this.frameListeners = this.frameListeners.filter((l) => l !== callback);
    };
  }

  onStatusChange(callback: (status: EngineStatus) => void): () => void {
    this.statusListeners.push(callback);
    return () => {
      this.statusListeners = this.statusListeners.filter((l) => l !== callback);
    };
  }

  onError(callback: (error: { code: string; message: string; recoverable: boolean }) => void): () => void {
    this.errorListeners.push(callback);
    return () => {
      this.errorListeners = this.errorListeners.filter((l) => l !== callback);
    };
  }

  private setStatus(status: EngineStatus): void {
    this.status = status;
    this.statusListeners.forEach((l) => l(status));
  }

  private async handleStartFailure(error: any): Promise<void> {
    this.retryCount++;
    const maxRetries = 3;
    const delays = [1000, 2000, 4000];

    if (this.retryCount <= maxRetries) {
      const delay = delays[this.retryCount - 1] || 4000;
      this.errorListeners.forEach((l) =>
        l({
          code: 'ENGINE_START_RETRY',
          message: `Engine start failed, retrying in ${delay / 1000}s... (attempt ${this.retryCount}/${maxRetries})`,
          recoverable: true,
        })
      );
      await new Promise((resolve) => setTimeout(resolve, delay));
    } else {
      this.setStatus('failed');
      this.errorListeners.forEach((l) =>
        l({
          code: 'ENGINE_START_FAILED',
          message: `Engine failed to start after ${maxRetries} attempts`,
          recoverable: false,
        })
      );
    }
  }
}

export const engineBridge = new EngineBridge();
export default EngineBridge;