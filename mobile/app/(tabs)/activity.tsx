import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { BigTitle, Card, EmptyView, GlassTop, Pill } from '@/components/ui';
import { listRecentLocalSessions, type LocalSessionSummary } from '@/local/localProjects';
import { spacing, useTheme } from '@/theme';

export default function ActivityScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const [items, setItems] = useState<LocalSessionSummary[]>([]);
  const [collapsed, setCollapsed] = useState(false);
  useFocusEffect(useCallback(() => { let active = true; void listRecentLocalSessions().then((rows) => active && setItems(rows)); return () => { active = false; }; }, []));
  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <ScrollView contentContainerStyle={{ paddingTop: insets.top + 8, paddingBottom: insets.bottom + 116 }} onScroll={(e) => setCollapsed(e.nativeEvent.contentOffset.y > 26)} scrollEventThrottle={16}>
        <BigTitle title="动态" sub="本机任务、工具与审批活动" />
        <View style={{ paddingHorizontal: spacing.pad, paddingTop: 14, gap: 10 }}>
          {items.length ? items.map((item) => <Card key={item.id} onPress={() => router.push({ pathname: '/local-agent', params: { sessionId: item.id, engineId: item.engineId || '' } })} style={{ padding: 15, flexDirection: 'row', gap: 12, alignItems: 'center' }}>
            <View style={{ width: 40, height: 40, borderRadius: 13, backgroundColor: t.bg3, alignItems: 'center', justifyContent: 'center' }}><Icons.clock size={19} color={t.acTx} /></View>
            <View style={{ flex: 1 }}><Text style={{ color: t.tx, fontSize: 14.5, fontWeight: '700' }}>{item.title}</Text><Text style={{ color: t.tx3, fontSize: 12, marginTop: 4 }}>{new Date(item.updatedAt).toLocaleString('zh-CN')}</Text></View>
            <Pill color={t.tx2} bg={t.bg3}>{item.status}</Pill>
            <Icons.chevron size={16} color={t.tx3} />
          </Card>) : <EmptyView title="暂无本地动态" subtitle="运行一次 Agent 后，过程记录会保存在本机" icon="clock" />}
        </View>
      </ScrollView>
      <GlassTop title="动态" collapsed={collapsed} />
    </View>
  );
}
