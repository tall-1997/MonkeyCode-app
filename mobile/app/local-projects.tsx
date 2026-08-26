/**
 * 本地项目 —— 本地工作区项目管理（特权模式下可访问任意路径）。
 * 支持：列表 / 创建目录并初始化 Git / 从 Git 克隆 / 删除（确认后）。
 * 数据落在本地 SQLite。
 */
import { useFocusEffect, useRouter, useNavigation } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { Card, EmptyView, GlassNav, IconButton, LoadingView, PrimaryButton } from '@/components/ui';
import { listLocalProjects, removeLocalProject, type LocalProject } from '@/local/localProjects';
import { permissionDetector } from '@/local/PermissionDetector';
import { useTheme, type Theme } from '@/theme';

export default function LocalProjectsScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();

  const [projects, setProjects] = useState<LocalProject[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const privileged = permissionDetector.isPrivileged();

  useFocusEffect(
    useCallback(() => {
      let active = true;
      void listLocalProjects().then((list) => { if (active) setProjects(list); }).finally(() => { if (active) setLoading(false); });
      return () => { active = false; };
    }, [])
  );

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    setProjects(await listLocalProjects());
    setRefreshing(false);
  }, []);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  const onDelete = useCallback((p: LocalProject) => {
    Alert.alert('删除项目', `确定要删除本地项目「${p.name}」吗？（仅移除记录，不删除目录文件）`, [
      { text: '取消', style: 'cancel' },
      { text: '删除', style: 'destructive', onPress: () => { void removeLocalProject(p.id).then(() => void listLocalProjects().then(setProjects)); } },
    ]);
  }, []);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="本地项目" onBack={leave} right={
        <IconButton icon="plus" onPress={() => router.push('/local-project-create')} />
      } />
      <ScrollView
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        contentContainerStyle={{ paddingTop: 60, paddingBottom: insets.bottom + 40, paddingHorizontal: 14 }}
      >
        {!privileged ? (
          <Card style={{ padding: 16, marginTop: 8 }}>
            <Text style={{ fontSize: 13.5, color: t.tx2, lineHeight: 20 }}>本地项目需要特权模式（Root + LSPosed）才能访问完整文件系统。当前为沙箱模式，仍可创建本地记录但文件操作受限。</Text>
          </Card>
        ) : null}

        <PrimaryButton block label="新建本地项目" icon="plus" onPress={() => router.push('/local-project-create')} style={{ marginTop: 8 }} />

        {loading ? <LoadingView label="加载项目…" /> : projects.length === 0 ? (
          <EmptyView title="还没有本地项目" subtitle="点击上方按钮创建本地工作区项目" icon="folder" />
        ) : (
          projects.map((p) => (
            <Card key={p.id} style={{ marginTop: 10, padding: 14 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 11 }}>
                <View style={{ width: 36, height: 36, borderRadius: 10, backgroundColor: t.bg4, alignItems: 'center', justifyContent: 'center' }}>
                  <Icons.folder size={19} color={t.acTx} sw={1.7} />
                </View>
                <View style={{ flex: 1, minWidth: 0 }}>
                  <Text numberOfLines={1} style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>{p.name}</Text>
                  <Text numberOfLines={1} style={{ fontSize: 11.5, color: t.tx3, marginTop: 2, fontFamily: 'monospace' }}>{p.path}</Text>
                  {p.remoteUrl ? <Text numberOfLines={1} style={{ fontSize: 11, color: t.tx3, marginTop: 1 }}>🔗 {p.remoteUrl}</Text> : null}
                </View>
                <IconButton icon="git" iconSize={17} size={34} color={t.acTx} sw={1.8} onPress={() => router.push({ pathname: '/local-repo', params: { path: p.path } })} />
                <IconButton icon="trash" iconSize={17} size={34} color={t.red} sw={1.8} onPress={() => onDelete(p)} />
              </View>
            </Card>
          ))
        )}
      </ScrollView>
    </View>
  );
}