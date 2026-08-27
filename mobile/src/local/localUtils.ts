/** 本地能力模块的纯工具函数（无 RN 依赖，便于单元测试）。 */

/** UTF-8 字符串 → Base64（RN 环境无 Node Buffer，使用浏览器式 btoa）。 */
export function utf8ToBase64(text: string): string {
  if (typeof btoa === 'function') {
    const bytes = unescape(encodeURIComponent(text));
    return btoa(bytes);
  }
  // 兜底：按 UTF-16 code unit 编码（仅 ASCII 安全）
  let bin = '';
  for (let i = 0; i < text.length; i++) {
    bin += String.fromCharCode(text.charCodeAt(i));
  }
  return globalThis.btoa ? globalThis.btoa(bin) : bin;
}

/** Base64 → UTF-8 字符串（反向）。 */
export function base64ToUtf8(b64: string): string {
  if (typeof atob === 'function') {
    return decodeURIComponent(escape(atob(b64)));
  }
  if (globalThis.atob) return decodeURIComponent(escape(globalThis.atob(b64)));
  return b64;
}

/** LWW 合并：取 updatedAt 较新的版本；相等时保留 existing。 */
export function lwwMerge<T extends { updatedAt: number }>(incoming: T, existing?: T): T {
  if (!existing) return incoming;
  return incoming.updatedAt >= existing.updatedAt ? incoming : existing;
}

/** 将 Change 列表按 updatedAt 升序排序（拉取时顺序应用）。 */
export function sortByUpdatedAt<T extends { updatedAt: number }>(list: T[]): T[] {
  return [...list].sort((a, b) => a.updatedAt - b.updatedAt);
}

/** 验证本地会话/引擎 id 是否为安全路径段（防路径遍历）。 */
export function isSafeSegment(id: string): boolean {
  if (!id || id.length > 128) return false;
  // 拒绝路径分隔符、NUL、冒号、相对路径段
  if (/[\/\\\0:]|\.\./.test(id)) return false;
  return true;
}

/** 文件大小人类可读格式化。 */
export function formatBytes(bytes: number): string {
  if (bytes == null || bytes < 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}