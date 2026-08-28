import { useFocusEffect, useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ApiError, deleteTask, listTasks, stopTask } from '@/api/client';
import type { ProjectTask } from '@/api/types';
import { SwipeableRow } from '@/components/SwipeableRow';
import { TaskCard } from '@/components/TaskCard';
import { EmptyView, GlassNav, LoadingView } from '@/components/ui';
import { spacing, useTheme } from '@/theme';
import { taskDisplayName } from '@/utils/format';

const PAGE_SIZE = 20;
const FILTERS = [
  { key: 'running', label: '进行中', status: 'pending,processing' },
  { key: 'done', label: '已结束', status: 'finished,error' },
];

export default function CloudTasksScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const [tasks, setTasks] = useState<ProjectTask[]>([]);
  const [filter, setFilter] = useState('running');
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [fetching, setFetching] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');
  const loadingRef = useRef(false);
  const didMountRef = useRef(false);
  const status = FILTERS.find((item) => item.key === filter)?.status ?? '';

  const fetchPage = useCallback(async (pageNumber: number, mode: 'first' | 'refresh' | 'more', taskStatus: string) => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    if (mode === 'first') setFetching(true);
    if (mode === 'more') setLoadingMore(true);
    setError('');
    try {
      const rows = await listTasks({ page: pageNumber, size: PAGE_SIZE, status: taskStatus });
      setTasks((current) => mode === 'more' ? [...current, ...rows] : rows);
      setPage(pageNumber);
      setHasMore(rows.length >= PAGE_SIZE);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '加载失败');
      if (mode !== 'more') setTasks([]);
    } finally {
      loadingRef.current = false;
      setFetching(false);
      setRefreshing(false);
      setLoadingMore(false);
    }
  }, []);

  useEffect(() => {
    setTasks([]);
    setHasMore(true);
    void fetchPage(1, 'first', status);
  }, [fetchPage, status]);

  useFocusEffect(useCallback(() => {
    if (!didMountRef.current) { didMountRef.current = true; return; }
    void fetchPage(1, 'refresh', status);
  }, [fetchPage, status]));

  const remove = useCallback((id: string) => setTasks((current) => current.filter((task) => task.id !== id)), []);
  const confirmAction = useCallback((task: ProjectTask, action: 'stop' | 'delete') => {
    const deleting = action === 'delete';
    Alert.alert(deleting ? '删除任务' : '终止任务', `${deleting ? '删除' : '终止'}「${taskDisplayName(task)}」？`, [
      { text: '取消', style: 'cancel' },
      { text: deleting ? '删除' : '终止', style: 'destructive', onPress: async () => {
        try {
          if (deleting) await deleteTask(task.id); else await stopTask(task.id);
          remove(task.id);
        } catch (reason) {
          Alert.alert('操作失败', reason instanceof ApiError ? reason.message : '请稍后重试');
        }
      } },
    ]);
  }, [remove]);

  const leave = () => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back(); else router.replace('/(tabs)/tasks');
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="云端任务" onBack={leave} right={<Pressable onPress={() => router.push('/new-task')}><Text style={{ color: t.acTx, fontWeight: '700' }}>新建</Text></Pressable>} />
      <FlatList
        data={tasks}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ paddingTop: insets.top + 82, paddingBottom: insets.bottom + 28, flexGrow: 1 }}
        ListHeaderComponent={<View style={{ flexDirection: 'row', gap: 8, paddingHorizontal: spacing.pad, paddingBottom: 12 }}>{FILTERS.map((item) => <Pressable key={item.key} onPress={() => setFilter(item.key)} style={{ paddingHorizontal: 16, height: 34, justifyContent: 'center', borderRadius: 99, backgroundColor: filter === item.key ? t.acGhost : t.bg2 }}><Text style={{ color: filter === item.key ? t.acTx : t.tx2, fontWeight: '600' }}>{item.label}</Text></Pressable>)}</View>}
        renderItem={({ item }) => {
          const running = item.status === 'pending' || item.status === 'processing';
          const actions = [
            ...(running ? [{ key: 'stop', label: '终止', icon: 'stop', color: '#fff', bg: t.amber, onPress: () => confirmAction(item, 'stop') }] : []),
            { key: 'delete', label: '删除', icon: 'trash', color: '#fff', bg: t.red, onPress: () => confirmAction(item, 'delete') },
          ];
          return <View style={{ paddingHorizontal: spacing.pad }}><SwipeableRow actions={actions}><TaskCard task={item} onPress={() => router.push(`/task/${item.id}`)} /></SwipeableRow></View>;
        }}
        ItemSeparatorComponent={() => <View style={{ height: spacing.gap }} />}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); void fetchPage(1, 'refresh', status); }} tintColor={t.ac} />}
        onEndReached={() => { if (hasMore && !loadingRef.current) void fetchPage(page + 1, 'more', status); }}
        onEndReachedThreshold={0.4}
        ListEmptyComponent={fetching ? <LoadingView label="加载任务…" /> : <EmptyView title={error ? '加载失败' : '暂无云端任务'} subtitle={error || undefined} icon={error ? 'alert' : 'robot'} />}
        ListFooterComponent={loadingMore ? <ActivityIndicator color={t.ac} style={{ margin: 20 }} /> : null}
      />
    </View>
  );
}
