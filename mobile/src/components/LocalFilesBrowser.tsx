/**
 * 本地文件浏览器 —— 特权模式下浏览/读写设备文件系统（Root 提权）。
 * 沙箱模式下灰显提示需要 Root。支持目录导航、文件内容查看（文本）、返回上层。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import * as FileSystem from 'expo-file-system/legacy';
import { Icons } from '@/components/Icons';
import { Card, EmptyView, LoadingView, PrimaryButton } from '@/components/ui';
import { privilegedApi } from '@/local/privilegedApi';
import { permissionDetector } from '@/local/PermissionDetector';
import { useTheme, type Theme } from '@/theme';

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  modificationTime: number;
}

export function LocalFilesBrowser({ startPath, enabled = true }: { startPath?: string; enabled?: boolean }) {
  // 特权模式 → 完整文件系统；沙箱模式 → App 私有文档目录（免 root）
  const initialPath = startPath ?? (permissionDetector.isPrivileged() ? '/sdcard' : (FileSystem.documentDirectory ?? ''));
  const t = useTheme();
  const [cwd, setCwd] = useState(initialPath);
  const [entries, setEntries] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [content, setContent] = useState<{ path: string; text: string } | null>(null);
  const [newPath, setNewPath] = useState('');

  const load = useCallback(async (dir: string) => {
    setLoading(true);
    setError('');
    try {
      const list = await privilegedApi.listDirectory(dir);
      setEntries(list);
      setCwd(dir);
    } catch (e: any) {
      setError(e.message || '目录加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!enabled) return;
    void load(initialPath);
  }, [enabled, initialPath, load]);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    await load(cwd);
    setRefreshing(false);
  }, [cwd, load]);

  const openEntry = useCallback(async (entry: FileEntry) => {
    if (entry.isDirectory) {
      await load(entry.path);
      return;
    }
    // 文本文件预览（限制 5MB，仅按 utf8 尝试读取）
    try {
      const text = await privilegedApi.readFile(entry.path);
      setContent({ path: entry.path, text });
    } catch {
      Alert.alert('无法读取', '该文件不是可预览的文本文件。');
    }
  }, [load]);

  if (!enabled) {
    return (
      <View style={{ padding: 20 }}>
        <EmptyView title="本地文件暂不可用" subtitle="请先在「特权能力设置」安装 Linux 工具环境（PRoot 免 root），或获取 Root 权限浏览完整文件系统。" icon="folder" />
      </View>
    );
  }

  if (loading && !refreshing) return <LoadingView label="加载目录…" />;

  return (
    <View style={{ flex: 1 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 14, paddingTop: 8 }}>
        <Icons.folder size={15} color={t.tx3} sw={1.7} />
        <Text numberOfLines={1} style={{ flex: 1, fontSize: 12, color: t.tx3, fontFamily: 'monospace' }}>{cwd}</Text>
        {cwd !== '/sdcard' && (
          <Pressable onPress={() => { const up = cwd.slice(0, cwd.lastIndexOf('/')) || '/'; void load(up); }} hitSlop={8} style={{ padding: 4 }}>
            <Icons.back size={16} color={t.tx2} sw={1.8} style={{ transform: [{ rotate: '0deg' }] }} />
          </Pressable>
        )}
        <Pressable onPress={refresh} hitSlop={8} style={{ padding: 4 }}>
          <Icons.refresh size={15} color={t.tx2} sw={1.8} />
        </Pressable>
      </View>

      {error ? <Text style={{ color: t.red, fontSize: 12, paddingHorizontal: 14, paddingVertical: 6 }}>{error}</Text> : null}

      <ScrollView refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} />} contentContainerStyle={{ paddingBottom: 24 }}>
        {content ? (
          <View style={{ marginHorizontal: 12, marginTop: 8 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
              <Text numberOfLines={1} style={{ flex: 1, fontSize: 13, fontWeight: '600', color: t.tx, marginRight: 8 }}>{content.path}</Text>
              <Pressable onPress={() => setContent(null)} hitSlop={8}><Icons.back size={16} color={t.tx3} sw={1.8} /></Pressable>
            </View>
            <View style={{ backgroundColor: t.termBg, borderRadius: 12, padding: 12 }}>
              <ScrollView style={{ maxHeight: 340 }} nestedScrollEnabled>
                <Text selectable style={{ color: t.termTx, fontSize: 12.5, fontFamily: 'monospace', lineHeight: 19 }}>{content.text}</Text>
              </ScrollView>
            </View>
          </View>
        ) : (
          entries.map((e) => (
            <Pressable key={e.path} onPress={() => void openEntry(e)} style={({ pressed }) => [{ flexDirection: 'row', alignItems: 'center', gap: 11, paddingHorizontal: 14, paddingVertical: 11, borderTopWidth: StyleSheet.hairlineWidth, borderColor: t.line }, pressed && { backgroundColor: t.bg3 }]}>
              {e.isDirectory
                ? <Icons.folder size={19} color={t.acTx} sw={1.7} />
                : <Icons.file size={18} color={t.tx3} sw={1.6} />}
              <View style={{ flex: 1, minWidth: 0 }}>
                <Text numberOfLines={1} style={{ fontSize: 14, fontWeight: '500', color: t.tx }}>{e.name}</Text>
                {!e.isDirectory && e.size > 0 ? <Text style={{ fontSize: 11, color: t.tx3, marginTop: 1 }}>{formatSize(e.size)}</Text> : null}
              </View>
              {e.isDirectory ? <Icons.chevron size={15} color={t.tx3} sw={1.9} /> : null}
            </Pressable>
          ))
        )}
      </ScrollView>
    </View>
  );
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${bytes} B`;
}

export default LocalFilesBrowser;