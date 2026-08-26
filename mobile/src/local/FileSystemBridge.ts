import { NativeModules, Platform, NativeEventEmitter } from 'react-native';
import { permissionDetector, FileEntry, FileInfo } from './PermissionDetector';
import * as ExpoFileSystem from 'expo-file-system';

const { PrivilegedExecution } = NativeModules;

class FileSystemBridge {
  private mode: 'sandbox' | 'privileged' = 'sandbox';

  constructor() {
    this.mode = permissionDetector.getMode();
    permissionDetector.addListener((state) => {
      this.mode = state.mode;
    });
  }

  getMode(): 'sandbox' | 'privileged' {
    return this.mode;
  }

  async listDirectory(path: string): Promise<FileEntry[]> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      return PrivilegedExecution.listDirectory(path);
    }
    // 沙箱模式：使用 expo-file-system
    const items = await ExpoFileSystem.readDirectoryAsync(path);
    const entries: FileEntry[] = [];
    for (const name of items) {
      const fullPath = `${path}/${name}`;
      const info = await ExpoFileSystem.getInfoAsync(fullPath);
      entries.push({
        name,
        path: fullPath,
        isDirectory: info.isDirectory ?? false,
        size: info.size ?? 0,
        modificationTime: info.modificationTime ?? 0,
      });
    }
    return entries.sort((a, b) => {
      if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }

  async readFile(path: string, encoding: 'utf8' | 'base64' = 'utf8'): Promise<string> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      return PrivilegedExecution.readFile(path, encoding);
    }
    const content = await ExpoFileSystem.readAsStringAsync(path, {
      encoding: encoding === 'base64' ? ExpoFileSystem.EncodingType.Base64 : ExpoFileSystem.EncodingType.UTF8,
    });
    return content;
  }

  async writeFile(path: string, content: string): Promise<void> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      await PrivilegedExecution.writeFile(path, content);
      return;
    }
    await ExpoFileSystem.writeAsStringAsync(path, content);
  }

  async createDirectory(path: string): Promise<void> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      await PrivilegedExecution.createDirectory(path);
      return;
    }
    await ExpoFileSystem.makeDirectoryAsync(path, { intermediates: true });
  }

  async deleteEntry(path: string): Promise<void> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      await PrivilegedExecution.deleteEntry(path);
      return;
    }
    await ExpoFileSystem.deleteAsync(path, { idempotent: false });
  }

  async getInfo(path: string): Promise<FileInfo> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      return PrivilegedExecution.getFileInfo(path);
    }
    const info = await ExpoFileSystem.getInfoAsync(path);
    return {
      exists: info.exists,
      isDirectory: info.isDirectory ?? false,
      size: info.size ?? 0,
      modificationTime: info.modificationTime ?? 0,
    };
  }
}

export const fileSystemBridge = new FileSystemBridge();
export default FileSystemBridge;