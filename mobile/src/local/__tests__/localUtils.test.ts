/** localUtils 纯函数测试。 */
import { base64ToUtf8, formatBytes, isSafeSegment, lwwMerge, sortByUpdatedAt, utf8ToBase64 } from '../localUtils';

describe('utf8ToBase64 / base64ToUtf8', () => {
  test('ASCII 往返一致', () => {
    expect(utf8ToBase64('hello')).toBe('aGVsbG8=');
    expect(base64ToUtf8('aGVsbG8=')).toBe('hello');
  });

  test('中文 UTF-8 往返一致', () => {
    const b64 = utf8ToBase64('你好，世界');
    expect(base64ToUtf8(b64)).toBe('你好，世界');
  });
});

describe('lwwMerge LWW 合并', () => {
  test('incoming 较新则采纳 incoming', () => {
    expect(lwwMerge({ updatedAt: 200 }, { updatedAt: 100 })).toEqual({ updatedAt: 200 });
  });
  test('existing 较新则保留 existing', () => {
    expect(lwwMerge({ updatedAt: 100 }, { updatedAt: 200 })).toEqual({ updatedAt: 200 });
  });
  test('无 existing 时直接采纳 incoming', () => {
    expect(lwwMerge({ updatedAt: 50 }, undefined)).toEqual({ updatedAt: 50 });
  });
  test('时间相等保留 existing（稳定偏好）', () => {
    expect(lwwMerge({ updatedAt: 100 }, { updatedAt: 100 })).toEqual({ updatedAt: 100 });
  });
});

describe('sortByUpdatedAt', () => {
  test('按 updatedAt 升序', () => {
    expect(sortByUpdatedAt([{ updatedAt: 3 }, { updatedAt: 1 }, { updatedAt: 2 }].map((x) => ({ ...x, updatedAt: x.updatedAt }))))
      .toEqual([{ updatedAt: 1 }, { updatedAt: 2 }, { updatedAt: 3 }].map((x) => ({ ...x, updatedAt: x.updatedAt })));
  });
  test('不修改原数组', () => {
    const arr = [{ updatedAt: 2 }, { updatedAt: 1 }];
    const copy = [...arr];
    sortByUpdatedAt(arr);
    expect(arr).toEqual(copy);
  });
});

describe('isSafeSegment 路径安全校验', () => {
  test('合法 id 放行', () => {
    expect(isSafeSegment('session_123abc')).toBe(true);
    expect(isSafeSegment('a-b_c.d')).toBe(true);
  });
  test('拒绝路径分隔符', () => {
    expect(isSafeSegment('../etc')).toBe(false);
    expect(isSafeSegment('a/b')).toBe(false);
    expect(isSafeSegment('a\\b')).toBe(false);
  });
  test('拒绝 NUL / 冒号 / 超长', () => {
    expect(isSafeSegment('a\u0000b')).toBe(false);
    expect(isSafeSegment('a:b')).toBe(false);
    expect(isSafeSegment('x'.repeat(129))).toBe(false);
    expect(isSafeSegment('')).toBe(false);
  });
});

describe('formatBytes', () => {
  test('各种量级', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(formatBytes(-1)).toBe('');
    expect(formatBytes(undefined as never)).toBe('');
  });
});