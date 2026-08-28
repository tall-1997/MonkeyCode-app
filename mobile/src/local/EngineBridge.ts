import { DeviceEventEmitter, NativeModules } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system';
import { Paths } from 'expo-file-system';

const { PrivilegedExecution } = NativeModules;

const ENGINE_MAX_RETRY = 5;
const ENGINE_STABLE_UPTIME_MS = 60_000;
const ENGINE_LOG_DIR = 'logs';

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
    interfaceType?: 'openai_chat' | 'openai_responses' | 'anthropic';
  };
  systemPrompt?: string;
  initialInput?: string;
  skills?: string[];
  maxTurns?: number;
  streamEnabled?: boolean;
  compactThreshold?: number;
}

export type EngineStatus = 'stopped' | 'starting' | 'ready' | 'crashed' | 'failed';

export type PermOutcome = 'approved' | 'denied' | 'timeout' | 'cancelled';

export interface EngineStatusDetail {
  phase: string;
  version?: string;
  attempt?: number;
  detail?: string;
  log_tail?: string;
  retry_in_ms?: number;
  error?: string;
}

export interface EngineFrame {
  type: string;
  kind?: string;
  data?: any;
  timestamp: number;
  seq: number;
}

export interface SubagentConfig {
  type: 'explore' | 'plan' | 'worker' | 'general-purpose';
  name: string;
  description?: string;
  systemPrompt?: string;
  task: string;
  maxTurns?: number;
  writePaths?: string[];
  model?: string;
  baseUrl?: string;
  apiKey?: string;
}

export interface ReplayWindow {
  frames: EngineFrame[];
  cursor: number;
  hasMore: boolean;
}

export interface SessionMeta {
  id: string;
  title: string;
  summary: string;
  status: string;
  engineId: string;
  createdAt: number;
  updatedAt: number;
}

function nextRetry(attempt: number, uptimeMs: number): { attempt: number; delayMs: number } | null {
  const used = uptimeMs >= ENGINE_STABLE_UPTIME_MS ? 0 : attempt;
  if (used >= ENGINE_MAX_RETRY) {
    return null;
  }
  const next = used + 1;
  return { attempt: next, delayMs: (1 << (used)) * 1000 };
}

function getLogDir(): string {
  return `${Paths.document.uri}${ENGINE_LOG_DIR}`;
}

class EngineBridge {
  private status: EngineStatus = 'stopped';
  private statusDetail: EngineStatusDetail = { phase: 'stopped' };
  private currentSessionId: string | null = null;
  private instanceNum: number = 0;
  private readyAt: number = 0;
  private frameListeners: Array<(frame: EngineFrame) => void> = [];
  private statusListeners: Array<(status: EngineStatus) => void> = [];
  private statusDetailListeners: Array<(detail: EngineStatusDetail) => void> = [];
  private errorListeners: Array<(error: { code: string; message: string; recoverable: boolean }) => void> = [];
  private retryCount: number = 0;
  private _frameSub: { remove: () => void } | null = null;
  private _statusSub: { remove: () => void } | null = null;
  private logDir: string = '';

  constructor() {
    this.logDir = getLogDir();
    if (PrivilegedExecution) {
      this._frameSub = DeviceEventEmitter.addListener('engineFrame', (raw: EngineFrame) => {
        const frame: EngineFrame = {
          type: raw.type,
          kind: raw.kind,
          timestamp: raw.timestamp,
          seq: raw.seq,
          data: raw.data === null || raw.data === undefined ? undefined : normalizeFrameData(raw.data),
        };
        this.frameListeners.forEach((l) => l(frame));
      });
      this._statusSub = DeviceEventEmitter.addListener('engineStatus', (event: EngineStatus | (EngineStatusDetail & { status: EngineStatus })) => {
        const status = typeof event === 'string' ? event : event.status;
        this.status = status;
        if (typeof event === 'object') this.setStatusDetail(event);
        this.statusListeners.forEach((l) => l(status));
      });
    }
  }

  getStatus(): EngineStatus {
    return this.status;
  }

  getStatusDetail(): EngineStatusDetail {
    return { ...this.statusDetail };
  }

  getCurrentSessionId(): string | null {
    return this.currentSessionId;
  }

  getInstanceNum(): number {
    return this.instanceNum;
  }

  /** 启动本地自研 Agent。返回引擎会话 id。 */
  async startEngine(config: EngineConfig): Promise<string> {
    if (this.status === 'ready' || this.status === 'starting') {
      return this.currentSessionId || '';
    }
    this.instanceNum++;
    this.retryCount = 0;
    this.setStatusDetail({ phase: 'starting', attempt: 0 });
    this.setStatus('starting');
    try {
      if (!PrivilegedExecution) throw new Error('特权模块不可用');
      const sid = await PrivilegedExecution.startAgent(JSON.stringify(config));
      this.currentSessionId = sid;
      this.readyAt = Date.now();
      this.setStatusDetail({ phase: 'ready', version: String(this.instanceNum) });
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

  /** 审批权限请求。 */
  async approvePermission(permId: string, remember: boolean = false): Promise<void> {
    if (!this.currentSessionId) throw new Error('No active session');
    if (PrivilegedExecution) {
      await PrivilegedExecution.approvePermission(permId, remember);
    }
  }

  /** 拒绝权限请求。 */
  async denyPermission(permId: string): Promise<void> {
    if (!this.currentSessionId) throw new Error('No active session');
    if (PrivilegedExecution) {
      await PrivilegedExecution.denyPermission(permId);
    }
  }

  /** 创建子代理。 */
  async spawnAgent(config: SubagentConfig): Promise<string | null> {
    if (!this.currentSessionId) throw new Error('No active session');
    if (PrivilegedExecution) {
      return await PrivilegedExecution.spawnAgent(JSON.stringify(config), this.currentSessionId);
    }
    throw new Error('特权模块不可用');
  }

  /** 取消子代理。 */
  async cancelSubagent(childId: string): Promise<void> {
    if (PrivilegedExecution) {
      await PrivilegedExecution.cancelSubagent(childId);
    }
  }

  /** 会话持久化：从 SessionManager 恢复会话列表。 */
  async listSessions(): Promise<SessionMeta[]> {
    if (PrivilegedExecution) {
      return await PrivilegedExecution.listSessions();
    }
    return [];
  }

  /** 打开已有会话，返回尾部回放窗口。 */
  async openSession(sessionId: string): Promise<{ frames: EngineFrame[]; cursor: string; has_more: boolean }> {
    if (!PrivilegedExecution) throw new Error('特权模块不可用');
    return await PrivilegedExecution.openSession(sessionId);
  }

  /** 删除会话及其 sidecar。 */
  async deleteSession(sessionId: string): Promise<void> {
    if (PrivilegedExecution) {
      await PrivilegedExecution.deleteSession(sessionId);
    }
  }

  /** 获取会话历史帧（按 cursor 翻页）。 */
  async getSessionHistory(sessionId: string, cursor: string, limit: number = 50): Promise<{ frames: EngineFrame[]; cursor: string; has_more: boolean }> {
    if (!PrivilegedExecution) throw new Error('特权模块不可用');
    return await PrivilegedExecution.getSessionHistory(sessionId, cursor, limit);
  }

  /** 按 seq 回读被截断的工具大字段。 */
  async getSessionFrame(sessionId: string, seq: number): Promise<EngineFrame | null> {
    if (!PrivilegedExecution) throw new Error('特权模块不可用');
    return await PrivilegedExecution.getSessionFrame(sessionId, seq);
  }

  async stopEngine(): Promise<void> {
    if (this.status === 'stopped') return;
    try {
      if (PrivilegedExecution) await PrivilegedExecution.stopAgent();
    } catch (_) {
    } finally {
      this.setStatus('stopped');
      this.setStatusDetail({ phase: 'stopped' });
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

  onStatusDetailChange(callback: (detail: EngineStatusDetail) => void): () => void {
    this.statusDetailListeners.push(callback);
    return () => {
      this.statusDetailListeners = this.statusDetailListeners.filter((l) => l !== callback);
    };
  }

  onError(callback: (error: { code: string; message: string; recoverable: boolean }) => void): () => void {
    this.errorListeners.push(callback);
    return () => {
      this.errorListeners = this.errorListeners.filter((l) => l !== callback);
    };
  }

  /** 引擎崩溃处理：退避重试或熔断，会话和解，日志留存。 */
  async handleEngineCrash(detail: string): Promise<void> {
    const oldInstance = this.instanceNum;
    const uptime = this.readyAt > 0 ? Date.now() - this.readyAt : 0;
    const decision = nextRetry(this.retryCount, uptime);
    const logTail = await this.captureEngineLog();
    if (decision) {
      this.retryCount = decision.attempt;
      this.setStatusDetail({
        phase: 'crashed',
        detail,
        log_tail: logTail,
        attempt: this.retryCount,
        retry_in_ms: decision.delayMs,
      });
      this.setStatus('crashed');
      this.emitSessionReconciliationFrames();
      this.errorListeners.forEach((l) =>
        l({
          code: 'ENGINE_CRASHED',
          message: `引擎已退出,将在 ${decision.delayMs / 1000}s 后自动重启 (${this.retryCount}/${ENGINE_MAX_RETRY})`,
          recoverable: true,
        })
      );
      await new Promise((resolve) => setTimeout(resolve, decision.delayMs));
      if (this.instanceNum === oldInstance) {
        this.setStatusDetail({ phase: 'starting', attempt: this.retryCount });
        this.setStatus('starting');
      }
    } else {
      this.setStatusDetail({
        phase: 'crashed',
        detail,
        log_tail: logTail,
        attempt: this.retryCount,
        retry_in_ms: undefined,
      });
      this.setStatus('failed');
      this.setStatusDetail({ phase: 'failed', error: `引擎启动失败,已熔断 (${ENGINE_MAX_RETRY} 次重试)` });
      this.emitSessionReconciliationFrames();
      this.errorListeners.forEach((l) =>
        l({
          code: 'ENGINE_FAILED',
          message: `引擎启动失败,已熔断 (${ENGINE_MAX_RETRY} 次重试)`,
          recoverable: false,
        })
      );
    }
  }

  /** 冷修复：检测 running 状态残留，补写收尾帧。 */
  async coldFixDetectRunningState(): Promise<void> {
    if (this.currentSessionId && this.status === 'ready') {
      this.emitSessionReconciliationFrames();
    }
  }

  private setStatus(status: EngineStatus): void {
    this.status = status;
    this.statusListeners.forEach((l) => l(status));
  }

  private setStatusDetail(detail: EngineStatusDetail): void {
    this.statusDetail = { ...this.statusDetail, ...detail };
    this.statusDetailListeners.forEach((l) => l({ ...this.statusDetail }));
  }

  private async handleStartFailure(error: any): Promise<void> {
    const uptime = this.readyAt > 0 ? Date.now() - this.readyAt : 0;
    const decision = nextRetry(this.retryCount, uptime);
    if (decision) {
      this.retryCount = decision.attempt;
      this.errorListeners.forEach((l) =>
        l({
          code: 'ENGINE_START_RETRY',
          message: `Engine start failed, retrying in ${decision.delayMs / 1000}s... (attempt ${this.retryCount}/${ENGINE_MAX_RETRY})`,
          recoverable: true,
        })
      );
      this.setStatusDetail({ phase: 'starting', attempt: this.retryCount });
      await new Promise((resolve) => setTimeout(resolve, decision.delayMs));
    } else {
      this.setStatus('failed');
      this.setStatusDetail({ phase: 'failed', error: `Engine failed to start after ${ENGINE_MAX_RETRY} attempts` });
      this.errorListeners.forEach((l) =>
        l({
          code: 'ENGINE_START_FAILED',
          message: `Engine failed to start after ${ENGINE_MAX_RETRY} attempts`,
          recoverable: false,
        })
      );
    }
  }

  private async captureEngineLog(): Promise<string> {
    try {
      const logPath = `${this.logDir}/engine.log`;
      const info = await ExpoFileSystem.getInfoAsync(logPath);
      if (!info.exists) return '';
      const content = await ExpoFileSystem.readAsStringAsync(logPath, { encoding: 'utf8' });
      const lines = content.split('\n');
      const tail = lines.slice(Math.max(0, lines.length - 20)).join('\n');
      return tail;
    } catch {
      return '';
    }
  }

  private emitSessionReconciliationFrames(): void {
    const now = Date.now();
    this.frameListeners.forEach((l) => {
      l({
        type: 'task-error',
        kind: 'error',
        timestamp: now,
        seq: 0,
        data: { message: '引擎已退出,会话异常终止' },
      });
      l({
        type: 'task-ended',
        kind: 'end',
        timestamp: now,
        seq: 0,
        data: { status: 'error' },
      });
    });
  }
}

export const engineBridge = new EngineBridge();

export const approvePermission = (permissionId: string, remember = false): Promise<void> =>
  engineBridge.approvePermission(permissionId, remember);

export const denyPermission = (permissionId: string): Promise<void> =>
  engineBridge.denyPermission(permissionId);

/** native 侧 data 可能是 JSON 字符串（应被解析）或已反序列化的对象 */
function normalizeFrameData(data: any): any {
  if (typeof data === 'string') {
    try {
      return JSON.parse(data);
    } catch {
      return data;
    }
  }
  return data;
}
export default EngineBridge;
