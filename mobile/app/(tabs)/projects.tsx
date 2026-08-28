import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ApiError, listProjects } from '@/api/client';
import type { Project } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { Icons } from '@/components/Icons';
import { ProjectCard } from '@/components/ProjectCard';
import { BigTitle, EmptyView, GlassTop, LoadingView, PrimaryButton } from '@/components/ui';
import { listLocalProjects, type LocalProject } from '@/local/localProjects';
import { spacing, useTheme } from '@/theme';

const PAGE_LIMIT = 20;

export default function ProjectsScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const { authenticated } = useAuth();
  const [localProjects, setLocalProjects] = useState<LocalProject[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');
  const [collapsed, setCollapsed] = useState(false);
  const loadingRef = useRef(false);
  const didInitRef = useRef(false);

  const fetchPage = useCallback(async (nextCursor: string | undefined, mode: 'init' | 'refresh' | 'more') => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    if (mode === 'init') setLoading(true);
    if (mode === 'more') setLoadingMore(true);
    setError('');
    try {
      const res = await listProjects({ cursor: nextCursor, limit: PAGE_LIMIT });
      setProjects((prev) => (mode === 'more' ? [...prev, ...res.projects] : res.projects));
      setCursor(res.nextCursor);
      setHasMore(res.hasMore && !!res.nextCursor);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败');
      if (mode !== 'more') setProjects([]);
    } finally {
      loadingRef.current = false;
      setLoading(false);
      setRefreshing(false);
      setLoadingMore(false);
    }
  }, []);

  // 进入页面即刷新（与任务页一致）：首次显示加载态，之后静默刷新，
  // 这样新建项目后返回列表能立即看到。
  useFocusEffect(
    useCallback(() => {
      void listLocalProjects().then(setLocalProjects).finally(() => setLoading(false));
      if (authenticated) fetchPage(undefined, didInitRef.current ? 'refresh' : 'init');
      else { setProjects([]); setError(''); }
      didInitRef.current = true;
    }, [authenticated, fetchPage]),
  );

  const onRefresh = useCallback(() => { setRefreshing(true); setHasMore(true); fetchPage(undefined, 'refresh'); }, [fetchPage]);
  const onEndReached = useCallback(() => {
    if (!loadingRef.current && hasMore && cursor) fetchPage(cursor, 'more');
  }, [cursor, fetchPage, hasMore]);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      {loading ? (
        <LoadingView label="加载项目…" />
      ) : error && projects.length === 0 && localProjects.length === 0 ? (
        <EmptyView title="加载失败" subtitle={error} icon="alert" />
      ) : (
        <FlatList
          data={projects}
          keyExtractor={(p, i) => p.id ?? String(i)}
          renderItem={({ item }) => (
            <View style={{ paddingHorizontal: spacing.pad }}>
              <ProjectCard project={item} onPress={() => router.push(`/project/${item.id}`)} />
            </View>
          )}
          ItemSeparatorComponent={() => <View style={{ height: spacing.gap }} />}
          ListHeaderComponent={
            <View style={{ paddingBottom: 14 }}>
              <View style={{ flexDirection: 'row', alignItems: 'flex-start' }}>
                <View style={{ flex: 1 }}>
                  <BigTitle title="项目" sub={`${localProjects.length} 个本地项目${authenticated ? ` · ${projects.length} 个云端项目` : ''}`} />
                </View>
                <Pressable onPress={() => router.push('/local-project-create')} style={({ pressed }) => [{ flexDirection: 'row', alignItems: 'center', gap: 5, minHeight: 44, paddingHorizontal: 14, borderRadius: 99, backgroundColor: t.acGhost, marginRight: spacing.pad, marginTop: 10 }, pressed && { opacity: 0.6 }]}>
                  <Icons.plus size={16} color={t.acTx} sw={2.4} />
                  <Text style={{ color: t.acTx, fontSize: 13.5, fontWeight: '700' }}>本地新建</Text>
                </Pressable>
              </View>
              <View style={{ paddingHorizontal: spacing.pad, gap: 10, marginTop: 12 }}>
                {localProjects.map((project) => (
                  <Pressable key={project.id} onPress={() => router.push({ pathname: '/local-repo', params: { path: project.path } })} style={({ pressed }) => [{ minHeight: 72, borderRadius: 18, padding: 14, backgroundColor: t.bg2, flexDirection: 'row', alignItems: 'center', gap: 12 }, t.shCard, pressed && { transform: [{ scale: 0.985 }] }]}>
                    <View style={{ width: 42, height: 42, borderRadius: 13, backgroundColor: t.acGhost, alignItems: 'center', justifyContent: 'center' }}><Icons.folder size={21} color={t.acTx} /></View>
                    <View style={{ flex: 1 }}><Text style={{ color: t.tx, fontSize: 15, fontWeight: '700' }}>{project.name}</Text><Text numberOfLines={1} style={{ color: t.tx3, fontSize: 11.5, marginTop: 4, fontFamily: 'monospace' }}>{project.path}</Text></View>
                    <Text style={{ color: t.acTx, fontSize: 12, fontWeight: '700' }}>本机</Text>
                  </Pressable>
                ))}
                {!localProjects.length ? <Pressable onPress={() => router.push('/local-project-create')} style={{ minHeight: 72, borderRadius: 18, borderWidth: 1, borderStyle: 'dashed', borderColor: t.line2, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: t.tx2, fontSize: 13.5 }}>创建项目或克隆 Git 仓库</Text></Pressable> : null}
                <View style={{ flexDirection: 'row', alignItems: 'center', paddingTop: 8 }}><Text style={{ flex: 1, color: t.tx3, fontSize: 12, fontWeight: '700', letterSpacing: 0.5 }}>云端项目</Text><Pressable onPress={() => router.push(authenticated ? '/new-project' : '/login')} style={{ minHeight: 44, justifyContent: 'center' }}><Text style={{ color: t.acTx, fontSize: 13, fontWeight: '700' }}>{authenticated ? '新建云端项目' : '登录云端'}</Text></Pressable></View>
              </View>
            </View>
          }
          contentContainerStyle={{ paddingTop: insets.top + 8, paddingBottom: insets.bottom + 116 }}
          onScroll={(e) => { const y = e.nativeEvent.contentOffset.y; setCollapsed((c) => (c !== y > 26 ? y > 26 : c)); }}
          scrollEventThrottle={16}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={t.ac} progressViewOffset={insets.top + 46} />}
          onEndReached={onEndReached}
          onEndReachedThreshold={0.4}
          ListEmptyComponent={
            <View style={{ paddingTop: 40 }}>
               <EmptyView title={authenticated ? '暂无云端项目' : '云端账户可选'} subtitle={authenticated ? '可创建远程项目并关联仓库' : '本地项目与 Agent 可直接使用'} icon="folder" />
               <View style={{ paddingHorizontal: spacing.pad, marginTop: 18 }}>
                 <PrimaryButton block label={authenticated ? '新建云端项目' : '登录云端账户'} icon={authenticated ? 'plus' : 'globe'} onPress={() => router.push(authenticated ? '/new-project' : '/login')} />
              </View>
            </View>
          }
          ListFooterComponent={
            loadingMore ? <View style={{ paddingVertical: 20, alignItems: 'center' }}><ActivityIndicator color={t.ac} /></View>
              : !hasMore && projects.length > 0 ? <Text style={{ textAlign: 'center', color: t.tx3, fontSize: 11, paddingVertical: 18 }}>没有更多了</Text>
              : null
          }
        />
      )}
      <GlassTop title="项目" collapsed={collapsed} />
    </View>
  );
}
