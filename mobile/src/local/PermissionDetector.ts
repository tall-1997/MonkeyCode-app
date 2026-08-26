import { NativeModules, Platform } from 'react-native';

const { PrivilegedExecution } = NativeModules;

export interface RootInfo {
  available: boolean;
  manager: 'magisk' | 'kernelsu' | 'apatch' | 'unknown' | null;
  version: string | null;
  uid?: string;
}

export interface LSPosedInfo {
  available: boolean;
  version: string | null;
  apiVersion: number;
}

export type ExecutionMode = 'sandbox' | 'privileged';

export interface PermissionState {
  mode: ExecutionMode;
  root: RootInfo;
  lsposed: LSPosedInfo;
  capabilities: {
    shell: boolean;
    rootShell: boolean;
    fileSystem: boolean;
    systemAPI: boolean;
    personalData: boolean;
    guiAgent: boolean;
    systemHook: boolean;
    alpineLinux: boolean;
  };
}

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  modificationTime: number;
}

export interface FileInfo {
  exists: boolean;
  isDirectory: boolean;
  size: number;
  modificationTime: number;
}

export interface ShellResult {
  stdout: string;
  stderr: string;
  exitCode: number;
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

class PermissionDetector {
  private static instance: PermissionDetector;
  private permissionState: PermissionState | null = null;
  private listeners: Array<(state: PermissionState) => void> = [];

  private constructor() {}

  static getInstance(): PermissionDetector {
    if (!PermissionDetector.instance) {
      PermissionDetector.instance = new PermissionDetector();
    }
    return PermissionDetector.instance;
  }

  async detect(): Promise<PermissionState> {
    if (Platform.OS !== 'android') {
      const state = this.buildSandboxState();
      this.permissionState = state;
      return state;
    }

    if (!PrivilegedExecution) {
      const state = this.buildSandboxState();
      this.permissionState = state;
      return state;
    }

    try {
      const [rootInfo, lsposedInfo] = await Promise.all([
        PrivilegedExecution.detectRoot(),
        PrivilegedExecution.detectLSPosed(),
      ]);

      const isPrivileged = rootInfo.available && lsposedInfo.available;

      const state: PermissionState = {
        mode: isPrivileged ? 'privileged' : 'sandbox',
        root: rootInfo as RootInfo,
        lsposed: lsposedInfo as LSPosedInfo,
        capabilities: {
          shell: isPrivileged,
          rootShell: isPrivileged && !!rootInfo.available,
          fileSystem: isPrivileged,
          systemAPI: isPrivileged,
          personalData: isPrivileged,
          guiAgent: isPrivileged,
          systemHook: isPrivileged && lsposedInfo.available,
          alpineLinux: isPrivileged,
        },
      };

      this.permissionState = state;
      this.notifyListeners(state);
      return state;
    } catch {
      const state = this.buildSandboxState();
      this.permissionState = state;
      this.notifyListeners(state);
      return state;
    }
  }

  getState(): PermissionState | null {
    return this.permissionState;
  }

  getMode(): ExecutionMode {
    return this.permissionState?.mode ?? 'sandbox';
  }

  isPrivileged(): boolean {
    return this.permissionState?.mode === 'privileged';
  }

  addListener(listener: (state: PermissionState) => void): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  private notifyListeners(state: PermissionState): void {
    this.listeners.forEach((l) => l(state));
  }

  private buildSandboxState(): PermissionState {
    return {
      mode: 'sandbox',
      root: { available: false, manager: null, version: null },
      lsposed: { available: false, version: null, apiVersion: 0 },
      capabilities: {
        shell: false,
        rootShell: false,
        fileSystem: false,
        systemAPI: false,
        personalData: false,
        guiAgent: false,
        systemHook: false,
        alpineLinux: false,
      },
    };
  }
}

export const permissionDetector = PermissionDetector.getInstance();
export default PermissionDetector;