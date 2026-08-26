import { NativeModules, Platform, NativeEventEmitter } from 'react-native';
import { permissionDetector } from './PermissionDetector';

const { PrivilegedExecution } = NativeModules;

interface EngineConfig {
  binaryPath: string;
  configDir: string;
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
  skills?: string[];
}

type EngineStatus = 'stopped' | 'starting' | 'ready' | 'crashed' | 'failed';

interface EngineFrame {
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

  async startEngine(config: EngineConfig): Promise<void> {
    if (this.status === 'ready' || this.status === 'starting') return;

    this.setStatus('starting');
    this.retryCount = 0;

    try {
      if (PrivilegedExecution) {
        await PrivilegedExecution.startAgent(JSON.stringify(config));
      }
      this.setStatus('ready');
    } catch (e: any) {
      await this.handleStartFailure(e);
    }
  }

  async stopEngine(): Promise<void> {
    if (this.status === 'stopped') return;

    try {
      if (PrivilegedExecution) {
        await PrivilegedExecution.stopAgent();
      }
    } catch (_) {
    } finally {
      this.setStatus('stopped');
      this.currentSessionId = null;
    }
  }

  async restartEngine(): Promise<void> {
    this.retryCount = 0;
    await this.stopEngine();
    // 需要外部传入 config
  }

  async createSession(task: { description: string; attachments?: string[] }): Promise<string> {
    if (this.status !== 'ready') throw new Error('Engine not ready');
    const sessionId = `session_${Date.now()}`;
    this.currentSessionId = sessionId;
    return sessionId;
  }

  async sendInput(content: string): Promise<void> {
    if (!this.currentSessionId) throw new Error('No active session');
    // 通过 stdio 发送用户输入
    const frame: EngineFrame = {
      type: 'user-input',
      data: { content: Buffer.from(content).toString('base64') },
      timestamp: Date.now(),
      seq: 0,
    };
    this.frameListeners.forEach((l) => l(frame));
  }

  async cancelTask(): Promise<void> {
    if (PrivilegedExecution && this.currentSessionId) {
      await PrivilegedExecution.cancelAgent();
    }
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
      // 重试需要外部重新调用 startEngine
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