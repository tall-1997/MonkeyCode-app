import * as ExpoFileSystem from 'expo-file-system';
import { Paths } from 'expo-file-system';
import AsyncStorage from '@react-native-async-storage/async-storage';

const CONFIG_FILE = 'config.json';
const CONFIG_BAK = 'config.json.bak';
const OHMYAGENT_CONFIG_DIR_KEY = 'OHMYAGENT_CONFIG_DIR';

const DEFAULT_MODEL_CONTEXT_WINDOW = 200000;
const DEFAULT_MODEL_MAX_OUTPUT = 32768;
const DEFAULT_MODEL_THINK = 'low';

export interface ModelConfig {
  type: string;
  model: string;
  base_url: string;
  api_key: string;
  context_window: number;
  supports_images: boolean;
  max_output: number;
  thinking: { enabled: boolean; effort: string };
}

export interface AppConfig {
  models: ModelConfig[];
  mcp_servers: Record<string, any>;
  kernel_env: string;
  mc_base_url: string;
  mc_basic_auth: string;
  mc_llm_base_url: string;
  mc_skip_tls_verify: boolean;
  pet_enabled: boolean;
  sound_enabled: boolean;
  telemetry_enabled: boolean;
}

function defaultConfig(): AppConfig {
  return {
    models: [],
    mcp_servers: {},
    kernel_env: '',
    mc_base_url: '',
    mc_basic_auth: '',
    mc_llm_base_url: '',
    mc_skip_tls_verify: false,
    pet_enabled: true,
    sound_enabled: true,
    telemetry_enabled: true,
  };
}

const seq = (() => {
  let n = 0;
  return () => ++n;
})();

function tempFilePath(dir: string, label: string): string {
  return `${dir}/.config.json.${label}-${Date.now()}-${seq()}`;
}

function currentTimeMs(): number {
  return Date.now();
}

async function atomicWritePrivate(path: string, data: string): Promise<void> {
  const parent = path.substring(0, path.lastIndexOf('/'));
  await ExpoFileSystem.makeDirectoryAsync(parent, { intermediates: true });
  const tmp = tempFilePath(parent, 'tmp');
  try {
    await ExpoFileSystem.writeAsStringAsync(tmp, data, { encoding: 'utf8' });
    await ExpoFileSystem.moveAsync({ from: tmp, to: path });
  } catch (e) {
    const info = await ExpoFileSystem.getInfoAsync(tmp);
    if (info.exists) {
      await ExpoFileSystem.deleteAsync(tmp, { idempotent: true });
    }
    throw e;
  }
}

export class ConfigStore {
  private configDir: string;
  private lock: Promise<void> = Promise.resolve();

  constructor(configDir: string) {
    this.configDir = configDir;
  }

  private async withLock<T>(fn: () => Promise<T>): Promise<T> {
    const prev = this.lock;
    let release: () => void;
    this.lock = new Promise<void>((resolve) => {
      release = resolve;
    });
    await prev;
    try {
      return await fn();
    } finally {
      release!();
    }
  }

  async loadConfig(): Promise<AppConfig> {
    return this.withLock(async () => {
      return this.loadConfigUnlocked();
    });
  }

  async saveConfig(cfg: AppConfig): Promise<void> {
    return this.withLock(async () => {
      const merged = this.mergeShellPrefs(cfg);
      await this.saveConfigUnlocked(merged);
      await this.materializeEngineConfig(merged);
    });
  }

  async updateConfig(update: (cfg: AppConfig) => void): Promise<AppConfig> {
    return this.withLock(async () => {
      const cfg = await this.loadConfigUnlocked();
      update(cfg);
      await this.saveConfigUnlocked(cfg);
      await this.materializeEngineConfig(cfg);
      return cfg;
    });
  }

  private async loadConfigUnlocked(): Promise<AppConfig> {
    const path = `${this.configDir}/${CONFIG_FILE}`;
    const info = await ExpoFileSystem.getInfoAsync(path);
    if (!info.exists) {
      return defaultConfig();
    }
    let data: string;
    try {
      data = await ExpoFileSystem.readAsStringAsync(path, { encoding: 'utf8' });
    } catch (e: any) {
      throw new Error(`读取配置文件 ${path} 失败: ${e.message}`);
    }
    try {
      return JSON.parse(data) as AppConfig;
    } catch (primaryError: any) {
      const backupPath = `${this.configDir}/${CONFIG_BAK}`;
      const bakInfo = await ExpoFileSystem.getInfoAsync(backupPath);
      if (!bakInfo.exists) {
        throw new Error(
          `配置文件 ${path} 损坏: ${primaryError.message}；备份也不可用`
        );
      }
      let backupData: string;
      try {
        backupData = await ExpoFileSystem.readAsStringAsync(backupPath, {
          encoding: 'utf8',
        });
      } catch (e: any) {
        throw new Error(
          `${primaryError.message}；读取备份 ${backupPath} 也失败: ${e.message}`
        );
      }
      let cfg: AppConfig;
      try {
        cfg = JSON.parse(backupData) as AppConfig;
      } catch (e: any) {
        throw new Error(
          `配置文件 ${path} 损坏: ${primaryError.message}；备份也不可用: ${e.message}`
        );
      }
      const corrupt = `${this.configDir}/config.json.corrupt-${currentTimeMs()}-${seq()}`;
      await atomicWritePrivate(corrupt, data);
      await atomicWritePrivate(path, backupData);
      console.warn(
        `[config] config.json 损坏，已从 ${backupPath} 恢复；坏文件保存在 ${corrupt}`
      );
      return cfg;
    }
  }

  private async saveConfigUnlocked(cfg: AppConfig): Promise<void> {
    const path = `${this.configDir}/${CONFIG_FILE}`;
    const data = JSON.stringify(cfg, null, 2);
    const info = await ExpoFileSystem.getInfoAsync(path);
    if (info.exists) {
      try {
        const old = await ExpoFileSystem.readAsStringAsync(path, {
          encoding: 'utf8',
        });
        JSON.parse(old);
        await atomicWritePrivate(`${this.configDir}/${CONFIG_BAK}`, old);
      } catch {
        // 旧文件不可解析,不覆盖备份
      }
    }
    await atomicWritePrivate(path, data);
  }

  private mergeShellPrefs(incoming: AppConfig): AppConfig {
    return incoming;
  }

  private async materializeEngineConfig(cfg: AppConfig): Promise<void> {
    const engineConfigDir = process.env[OHMYAGENT_CONFIG_DIR_KEY] || `${this.configDir}/ohmyagent`;
    await ExpoFileSystem.makeDirectoryAsync(engineConfigDir, {
      intermediates: true,
    });
    const settings = this.buildEngineSettings(cfg);
    const settingsData = JSON.stringify(settings, null, 2);
    await atomicWritePrivate(`${engineConfigDir}/settings.json`, settingsData);
  }

  private buildEngineSettings(cfg: AppConfig): Record<string, any> {
    const modelsOut: Record<string, any> = {};
    let defaultModel = '';
    for (const m of cfg.models) {
      if (!m.model) continue;
      const entry: Record<string, any> = {
        type: m.type,
        model: m.model,
        base_url: m.base_url,
        api_key: m.api_key,
        context_window: m.context_window || DEFAULT_MODEL_CONTEXT_WINDOW,
        supports_images: m.supports_images || false,
        max_output: m.max_output || DEFAULT_MODEL_MAX_OUTPUT,
        thinking: m.thinking || { enabled: true, effort: DEFAULT_MODEL_THINK },
      };
      modelsOut[m.model] = entry;
      if (!defaultModel) {
        defaultModel = m.model;
      }
    }
    return {
      default_model: defaultModel,
      permission_mode: 'auto',
      models: modelsOut,
    };
  }
}

export async function getConfigDir(): Promise<string> {
  const docDir = Paths.document;
  if (!docDir) {
    throw new Error('无法定位应用文档目录');
  }
  return `${docDir.uri}config`;
}