import { NativeModules } from 'react-native';
import { permissionDetector, FileEntry, FileInfo } from './PermissionDetector';
import * as ExpoFileSystem from 'expo-file-system';
import { gitBridge } from './GitBridge';

const { PrivilegedExecution } = NativeModules;

const MAX_FILE_BYTES = 1 << 20; // 1MB
const MAX_LIST_ITEMS = 2000;
const UPLOAD_MAX_BYTES = 20 * 1024 * 1024;

class FileSystemBridge {
  private mode: 'sandbox' | 'privileged' = 'sandbox';
  private workDir: string = '';

  constructor() {
    this.mode = permissionDetector.getMode();
    permissionDetector.addListener((state) => {
      this.mode = state.mode;
    });
  }

  getMode(): 'sandbox' | 'privileged' {
    return this.mode;
  }

  setWorkDir(dir: string): void {
    this.workDir = dir;
  }

  resolvePath(rel: string): string {
    if (rel.startsWith('/') || rel.includes('\\')) {
      throw new Error(`路径 ${rel} 超出工作区`);
    }
    const components = rel.split('/');
    for (const c of components) {
      if (c === '..' || c === '.') {
        throw new Error(`路径 ${rel} 超出工作区`);
      }
    }
    if (!this.workDir) {
      return rel;
    }
    const joined = rel ? `${this.workDir}/${rel}` : this.workDir;
    return joined;
  }

  async listDirectory(path: string): Promise<FileEntry[]> {
    if (this.mode === 'privileged' && PrivilegedExecution) {
      return PrivilegedExecution.listDirectory(path);
    }
    const items = await ExpoFileSystem.readDirectoryAsync(path);
    const entries: FileEntry[] = [];
    for (const name of items) {
      if (name === '.git') {
        continue;
      }
      if (entries.length >= MAX_LIST_ITEMS) {
        break;
      }
      const fullPath = `${path}/${name}`;
      const info = await ExpoFileSystem.getInfoAsync(fullPath);
      entries.push({
        name,
        path: fullPath,
        isDirectory: info.isDirectory ?? false,
        size: (info as any).size ?? 0,
        modificationTime: (info as any).modificationTime ?? 0,
      });
    }
    return entries.sort((a, b) => {
      if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }

  async readFile(path: string, encoding: 'utf8' | 'base64' = 'utf8'): Promise<string> {
    const info = await ExpoFileSystem.getInfoAsync(path);
    if (info.exists && !info.isDirectory) {
      const size = (info as any).size ?? 0;
      if (size > MAX_FILE_BYTES) {
        throw new Error(`文件过大(${size} 字节),超过 ${MAX_FILE_BYTES} 上限`);
      }
    }
    if (this.mode === 'privileged' && PrivilegedExecution) {
      return PrivilegedExecution.readFile(path, encoding);
    }
    const options: any = {};
    if (encoding === 'base64') {
      options.encoding = 'base64';
    }
    const content = await ExpoFileSystem.readAsStringAsync(path, options);
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
      size: (info as any).size ?? 0,
      modificationTime: (info as any).modificationTime ?? 0,
    };
  }

  async getDiff(path: string, file?: string): Promise<string> {
    const resolved = this.resolvePath(path);
    return gitBridge.getDiff(resolved, file);
  }

  async readDataUrl(path: string): Promise<string> {
    const resolved = this.resolvePath(path);
    const info = await ExpoFileSystem.getInfoAsync(resolved);
    if (!info.exists) {
      throw new Error(`文件不存在: ${path}`);
    }
    if (info.isDirectory) {
      throw new Error(`${path} 是目录`);
    }
    const size = (info as any).size ?? 0;
    if (size > UPLOAD_MAX_BYTES) {
      throw new Error(`文件过大(${size} 字节,上限 ${UPLOAD_MAX_BYTES})`);
    }
    const mime = imageMimeFromPath(path);
    if (!mime) {
      throw new Error('仅支持工作区内的常见图片格式(PNG/JPEG/GIF/WebP)');
    }
    const base64 = await ExpoFileSystem.readAsStringAsync(resolved, {
      encoding: 'base64',
    });
    return `data:${mime};base64,${base64}`;
  }
}

function imageMimeFromPath(filePath: string): string | null {
  const ext = (filePath.split('.').pop() || '').toLowerCase();
  switch (ext) {
    case 'png': return 'image/png';
    case 'jpg':
    case 'jpeg': return 'image/jpeg';
    case 'gif': return 'image/gif';
    case 'webp': return 'image/webp';
    default: return null;
  }
}

export const fileSystemBridge = new FileSystemBridge();
export default FileSystemBridge;