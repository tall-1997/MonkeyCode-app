import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { useAuth } from '@/auth/AuthContext';
import { BigTitle, Card, GlassTop, Pill } from '@/components/ui';
import { listRecentLocalSessions, type LocalSessionSummary } from '@/local/localProjects';
import { permissionDetector, type PermissionState } from '@/local/PermissionDetector';
import { spacing, useTheme } from '@/theme';

export default function TasksScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const { authenticated } = useAuth();
  const [prompt, setPrompt] = useState('');
  const [sessions, setSessions] = useState<LocalSessionSummary[]>([]);
  const [permission, setPermission] = useState<PermissionState | null>(() => permissionDetector.getState());
  const [collapsed, setCollapsed] = useState(false);
  useFocusEffect(
    useCallback(() => {
      let active = true;
      void Promise.all([listRecentLocalSessions(4), permissionDetector.detect()]).then(([recent, state]) => {
        if (active) { setSessions(recent); setPermission(state); }
      });
      return () => { active = false; };
    }, []),
  );

  const start = () => router.push({ pathname: '/local-agent', params: prompt.trim() ? { prompt: prompt.trim() } : undefined });
  const mode = permission?.mode === 'privileged' ? '特权模式' : '安全沙箱';

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <ScrollView contentContainerStyle={{ paddingTop: insets.top + 8, paddingBottom: insets.bottom + 116 }}
        onScroll={(e) => { const y = e.nativeEvent.contentOffset.y; setCollapsed((c) => (c !== y > 26 ? y > 26 : c)); }}
        scrollEventThrottle={16}
      >
        <BigTitle title="Agent" sub="任务在本机运行，云端连接按需启用" />
        <View style={{ paddingHorizontal: spacing.pad, paddingTop: 14, gap: 12 }}>
          <Card style={{ padding: 16 }}>
            <Text style={{ color: t.tx, fontSize: 19, fontWeight: '700', lineHeight: 26 }}>今天想让 Agent 做什么？</Text>
            <TextInput value={prompt} onChangeText={setPrompt} multiline placeholder="描述任务、目标和约束…" placeholderTextColor={t.tx3}
              style={{ minHeight: 112, marginTop: 14, padding: 14, borderRadius: 16, backgroundColor: t.bg3, color: t.tx, fontSize: 15, lineHeight: 22, textAlignVertical: 'top' }} />
            <Pressable onPress={start} style={({ pressed }) => [{ height: 52, borderRadius: 16, marginTop: 12, backgroundColor: t.ac, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8 }, pressed && { transform: [{ scale: 0.98 }] }]}>
              <Icons.send size={18} color={t.acInk} sw={2.2} /><Text style={{ color: t.acInk, fontSize: 15, fontWeight: '800' }}>在本机运行</Text>
            </Pressable>
          </Card>

          <View style={{ flexDirection: 'row', gap: 10 }}>
            <Card style={{ flex: 1, padding: 14 }}><Icons.cube size={20} color={t.acTx} /><Text style={{ color: t.tx3, fontSize: 11.5, marginTop: 12 }}>运行环境</Text><Text style={{ color: t.tx, fontSize: 14, fontWeight: '700', marginTop: 3 }}>{mode}</Text></Card>
            <Card style={{ flex: 1, padding: 14 }}><Icons.shield size={20} color={t.acTx} /><Text style={{ color: t.tx3, fontSize: 11.5, marginTop: 12 }}>权限范围</Text><Text style={{ color: t.tx, fontSize: 14, fontWeight: '700', marginTop: 3 }}>{permission?.capabilities.rootShell ? 'Root 已连接' : '按需审批'}</Text></Card>
          </View>

          <Pressable onPress={() => router.push(authenticated ? '/cloud-tasks' : '/login')} style={{ minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 10, paddingHorizontal: 4 }}>
            <Icons.globe size={18} color={t.acTx} />
            <Text style={{ flex: 1, color: t.tx, fontSize: 14, fontWeight: '700' }}>{authenticated ? '管理云端任务' : '登录后使用云端任务'}</Text>
            <Icons.chevron size={17} color={t.tx3} />
          </Pressable>

          <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 6 }}><Text style={{ flex: 1, color: t.tx, fontSize: 17, fontWeight: '700' }}>最近会话</Text><Pressable onPress={() => router.push('/(tabs)/activity')} style={{ minHeight: 44, justifyContent: 'center', paddingHorizontal: 4 }}><Text style={{ color: t.acTx, fontWeight: '700' }}>查看全部</Text></Pressable></View>
          {sessions.length ? sessions.map((session) => (
            <Card key={session.id} onPress={() => router.push({ pathname: '/local-agent', params: { sessionId: session.id, engineId: session.engineId || '' } })} style={{ minHeight: 68, padding: 14, flexDirection: 'row', alignItems: 'center', gap: 12 }}>
              <View style={{ width: 38, height: 38, borderRadius: 12, backgroundColor: t.acGhost, alignItems: 'center', justifyContent: 'center' }}><Icons.robot size={19} color={t.acTx} /></View>
              <View style={{ flex: 1 }}><Text numberOfLines={1} style={{ color: t.tx, fontSize: 14.5, fontWeight: '600' }}>{session.title}</Text><Text style={{ color: t.tx3, fontSize: 12, marginTop: 4 }}>{new Date(session.updatedAt).toLocaleString('zh-CN')}</Text></View>
              <Pill color={t.acTx} bg={t.acGhost}>{session.status === 'running' ? '运行中' : '已结束'}</Pill>
              <Icons.chevron size={16} color={t.tx3} />
            </Card>
          )) : <Card style={{ padding: 18 }}><Text style={{ color: t.tx2, fontSize: 13.5 }}>首个本地会话会显示在这里。</Text></Card>}
        </View>
      </ScrollView>
      <GlassTop title="Agent" collapsed={collapsed} />
    </View>
  );
}
