import * as ExpoFileSystem from 'expo-file-system';

const UPLOAD_MAX_BYTES = 20 * 1024 * 1024; // 20MB

interface PendingUpload {
  partPath: string;
  destPath: string;
  relPath: string;
}

interface UploadHandle {
  uploadId: number;
}

const pending: Map<number, PendingUpload> = new Map();
let nextHandle = 1;

function sanitizeName(name: string): string | null {
  let base = name.replace(/\\/g, '/');
  const parts = base.split('/');
  base = parts[parts.length - 1] || '';
  const cleaned = base
    .split('')
    .map((ch) => {
      if (/[a-zA-Z0-9.\-_]/.test(ch)) return ch;
      if (ch >= '\u4e00' && ch <= '\u9fff') return ch;
      return '_';
    })
    .join('');
  const trimmed = cleaned.replace(/^[._]+/, '').replace(/[._]+$/, '');
  if (!trimmed || trimmed.length > 120) return null;
  return trimmed;
}

function imageExt(mediaType: string): string {
  switch (mediaType) {
    case 'image/png': return '.png';
    case 'image/jpeg': return '.jpg';
    case 'image/gif': return '.gif';
    case 'image/webp': return '.webp';
    default: return '.bin';
  }
}

function fallbackName(mediaType: string): string {
  const ext = imageExt(mediaType);
  const prefix = ext === '.bin' ? 'file-' : 'img-';
  const ts = Date.now();
  return `${prefix}${ts}${ext}`;
}

function reserveName(dir: string, fname: string): string {
  if (!fname) return fname;
  const dotIdx = fname.lastIndexOf('.');
  const stem = dotIdx > 0 ? fname.substring(0, dotIdx) : fname;
  const ext = dotIdx > 0 ? fname.substring(dotIdx) : '';
  let candidate = fname;
  let i = 2;
  while (pendingFiles.has(`${dir}/${candidate}`) || pendingFiles.has(`${dir}/${candidate}.part`)) {
    candidate = `${stem}-${i}${ext}`;
    i++;
  }
  return candidate;
}

const pendingFiles: Set<string> = new Set();

function uploadsDir(workDir: string): string {
  return `${workDir}/.monkeycode/uploads`;
}

async function ensureUploadsDir(workDir: string): Promise<string> {
  const dir = uploadsDir(workDir);
  await ExpoFileSystem.makeDirectoryAsync(dir, { intermediates: true });
  const gi = `${dir}/.gitignore`;
  const giInfo = await ExpoFileSystem.getInfoAsync(gi);
  if (!giInfo.exists) {
    await ExpoFileSystem.writeAsStringAsync(gi, '*\n');
  }
  return dir;
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

export async function uploadBegin(
  workDir: string,
  fileName: string,
  totalSize: number,
): Promise<UploadHandle> {
  const dir = await ensureUploadsDir(workDir);
  let fname = sanitizeName(fileName);
  if (!fname) {
    fname = fallbackName('application/octet-stream');
  }
  fname = reserveName(dir, fname);
  const partPath = `${dir}/${fname}.part`;
  const destPath = `${dir}/${fname}`;
  const relPath = `.monkeycode/uploads/${fname}`;

  const info = await ExpoFileSystem.getInfoAsync(partPath);
  if (info.exists) {
    throw new Error('上传文件已存在,请重试');
  }

  await ExpoFileSystem.writeAsStringAsync(partPath, '', { encoding: 'utf8' });
  const uploadId = nextHandle++;
  pending.set(uploadId, { partPath, destPath, relPath });
  pendingFiles.add(partPath);
  pendingFiles.add(destPath);
  return { uploadId };
}

export async function uploadChunk(
  uploadId: number,
  chunk: string,
  offset: number,
): Promise<void> {
  const p = pending.get(uploadId);
  if (!p) {
    throw new Error('上传已失效,请重试');
  }
  try {
    const raw = base64ToBytes(chunk);
    const existingInfo = await ExpoFileSystem.getInfoAsync(p.partPath);
    const existingSize = existingInfo.exists ? (existingInfo as any).size ?? 0 : 0;
    const padding = offset - existingSize;
    if (padding > 0) {
      const pad = new Uint8Array(padding);
      await appendBytes(p.partPath, pad);
    }
    await appendBytes(p.partPath, raw);
  } catch (e: any) {
    pending.delete(uploadId);
    pendingFiles.delete(p.partPath);
    pendingFiles.delete(p.destPath);
    const info = await ExpoFileSystem.getInfoAsync(p.partPath);
    if (info.exists) {
      await ExpoFileSystem.deleteAsync(p.partPath, { idempotent: true });
    }
    throw new Error(`写入文件失败: ${e.message}`);
  }
}

export async function uploadFinish(uploadId: number): Promise<{ path: string }> {
  const p = pending.get(uploadId);
  if (!p) {
    throw new Error('上传已失效,请重试');
  }
  pending.delete(uploadId);
  pendingFiles.delete(p.partPath);
  pendingFiles.delete(p.destPath);
  await ExpoFileSystem.moveAsync({ from: p.partPath, to: p.destPath });
  return { path: p.relPath };
}

export async function uploadAbort(uploadId: number): Promise<void> {
  const p = pending.get(uploadId);
  if (p) {
    pending.delete(uploadId);
    pendingFiles.delete(p.partPath);
    pendingFiles.delete(p.destPath);
    const info = await ExpoFileSystem.getInfoAsync(p.partPath);
    if (info.exists) {
      await ExpoFileSystem.deleteAsync(p.partPath, { idempotent: true });
    }
  }
}

export async function readUploadedFile(workDir: string, fileName: string): Promise<string> {
  const fname = sanitizeName(fileName);
  if (!fname) {
    throw new Error('文件名无效');
  }
  const dir = uploadsDir(workDir);
  const filePath = `${dir}/${fname}`;
  const info = await ExpoFileSystem.getInfoAsync(filePath);
  if (!info.exists) {
    throw new Error(`文件不存在: ${fileName}`);
  }
  if (info.isDirectory) {
    throw new Error(`${fileName} 是目录`);
  }
  const size = (info as any).size ?? 0;
  if (size > UPLOAD_MAX_BYTES) {
    throw new Error(`文件过大(${size} 字节,上限 ${UPLOAD_MAX_BYTES})`);
  }
  const mime = imageMimeFromPath(fname) || 'application/octet-stream';
  const base64 = await ExpoFileSystem.readAsStringAsync(filePath, {
    encoding: 'base64',
  });
  return `data:${mime};base64,${base64}`;
}

function base64ToBytes(b64: string): Uint8Array {
  const binaryStr = atob(b64);
  const bytes = new Uint8Array(binaryStr.length);
  for (let i = 0; i < binaryStr.length; i++) {
    bytes[i] = binaryStr.charCodeAt(i);
  }
  return bytes;
}

async function appendBytes(filePath: string, data: Uint8Array): Promise<void> {
  const info = await ExpoFileSystem.getInfoAsync(filePath);
  if (!info.exists) {
    const b64 = bytesToBase64(data);
    await ExpoFileSystem.writeAsStringAsync(filePath, b64, { encoding: 'base64' });
    return;
  }
  const existingB64 = await ExpoFileSystem.readAsStringAsync(filePath, {
    encoding: 'base64',
  });
  const newB64 = bytesToBase64(data);
  const combined = existingB64 + newB64;
  await ExpoFileSystem.writeAsStringAsync(filePath, combined, { encoding: 'base64' });
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export { sanitizeName };