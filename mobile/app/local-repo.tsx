/**
 * 本地仓库管理 —— 对本地项目执行 Git 状态 / 提交 / 推送 / 拉取 / 分支切换。
 * 特权模式下走 Alpine Linux 原生 git；沙箱模式尝试 isomorphic-git。
 */
import { useLocalSearchParams, useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { Alert, Modal, Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { PrivilegedBanner } from '@/components/PrivilegedBanner';
import { EmptyView, GlassNav, IconButton, PrimaryButton } from '@/components/ui';
import { gitBridge, type GitCommit, type GitFileStatus } from '@/local/GitBridge';
import { useTheme, type Theme } from '@/theme';

export default function LocalRepoScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const { path } = useLocalSearchParams<{ path: string }>();
  const repoPath = path || '/sdcard/MonkeyCode';

  const [status, setStatus] = useState<GitFileStatus[]>([]);
  const [branch, setBranch] = useState('');
  const [commits, setCommits] = useState<GitCommit[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  const [staged, setStaged] = useState<Set<string>>(new Set());
  const [commitModal, setCommitModal] = useState(false);
  const [msg, setMsg] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setErr('');
    try {
      const st = await gitBridge.getStatus(repoPath);
      setStatus(st.files);
      setBranch(st.currentBranch);
      const log = await gitBridge.getLog(repoPath, 15).catch(() => []);
      setCommits(log);
    } catch (e: any) {
      setErr(e.message || 'Git 加载失败（需 git 环境）');
    } finally {
      setLoading(false);
    }
  }, [repoPath]);

  React.useEffect(() => { void load(); }, [load]);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/local-projects');
  }, [navigation, router]);

  const toggleStage = (file: GitFileStatus) => {
    setStaged((prev) => {
      const next = new Set(prev);
      if (next.has(file.path)) {
        next.delete(file.path);
      } else {
        next.add(file.path);
      }
      return next;
    });
  };

  const doCommit = async () => {
    const m = msg.trim();
    const toStage = status
      .filter((f) => f.status === 'untracked' ? staged.has(f.path) : true)
      .map((f) => f.path);
    if (!m || toStage.length === 0) { Alert.alert('提示', '请输入提交信息并选择要提交的文件'); return; }
    try {
      await gitBridge.stageFiles(repoPath, toStage);
      await gitBridge.commit(repoPath, m);
      setMsg(''); setCommitModal(false); setStaged(new Set());
      await load();
      Alert.alert('已提交', '本地提交成功。');
    } catch (e: any) {
      Alert.alert('提交失败', e.message || '提交失败（需 git 环境）');
    }
  };

  const doPush = async () => {
    try { await gitBridge.push(repoPath); Alert.alert('已推送', '本地提交已推送到远程。'); }
    catch (e: any) { Alert.alert('推送失败', e.message); }
  };

  const doPull = async () => {
    try { await gitBridge.pull(repoPath); await load(); Alert.alert('已拉取', '远程更新已合并到本地。'); }
    catch (e: any) { Alert.alert('拉取失败', e.message); }
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="本地仓库" onBack={leave} />
      <PrivilegedBanner />
      <ScrollView
        refreshControl={<RefreshControl refreshing={loading} onRefresh={() => void load()} />}
        contentContainerStyle={{ paddingTop: 56, paddingBottom: insets.bottom + 40 }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 14, paddingTop: 4 }}>
          <Icons.git size={15} color={t.tx3} sw={1.7} />
          <Text numberOfLines={1} style={{ flex: 1, fontSize: 12, color: t.tx3, fontFamily: 'monospace' }}>{repoPath}</Text>
          {branch ? <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: t.acGhost, paddingHorizontal: 8, paddingVertical: 3, borderRadius: 99 }}><Icons.branch size={11} color={t.acTx} sw={1.8} /><Text style={{ fontSize: 11, fontWeight: '600', color: t.acTx }}>{branch}</Text></View> : null}
        </View>

        {err ? <Text style={{ color: t.red, fontSize: 12.5, paddingHorizontal: 14, paddingVertical: 8 }}>{err}</Text> : null}

        {!err && status.length === 0 && (
          <EmptyView title="工作区干净" subtitle="没有未提交的变更" icon="checkCircle" />
        )}

        {status.map((f) => (
          <Pressable key={f.path} onPress={() => toggleStage(f)} style={({ pressed }) => [{ flexDirection: 'row', alignItems: 'center', gap: 11, paddingHorizontal: 14, paddingVertical: 10, borderTopWidth: StyleSheet.hairlineWidth, borderColor: t.line }, pressed && { backgroundColor: t.bg3 }]}>
            <Pressable onPress={() => toggleStage(f)} style={{ width: 20, height: 20, borderRadius: 6, borderWidth: 1.5, borderColor: f.status === 'untracked' ? t.tx3 : t.ac, alignItems: 'center', justifyContent: 'center', backgroundColor: f.status !== 'untracked' || staged.has(f.path) ? t.ac : 'transparent' }}>
              {(f.status !== 'untracked' || staged.has(f.path)) && <Icons.check size={13} color={t.acInk} sw={2.8} />}
            </Pressable>
            <Text style={{ color: f.status === 'modified' ? t.amber : f.status === 'deleted' ? t.del : f.status === 'added' ? t.add : t.tx3, fontSize: 11, fontWeight: '700', width: 62 }}>{statusLabel(f.status)}</Text>
            <Text numberOfLines={1} style={{ flex: 1, fontSize: 13, color: t.tx, fontFamily: 'monospace' }}>{f.path}</Text>
          </Pressable>
        ))}

        {status.length > 0 && (
          <View style={{ flexDirection: 'row', gap: 8, paddingHorizontal: 14, marginTop: 14 }}>
            <PrimaryButton label="提交" icon="file" onPress={() => setCommitModal(true)} disabled={status.length === 0} style={{ flex: 1 }} />
            <PrimaryButton label="推送" icon="upload" onPress={doPush} style={{ flex: 1, backgroundColor: t.ac2 }} />
            <PrimaryButton label="拉取" icon="download" onPress={doPull} style={{ flex: 1, backgroundColor: t.ac2 }} />
          </View>
        )}

        {commits.length > 0 && (
          <View style={{ marginTop: 18, paddingHorizontal: 14 }}>
            <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 6 }}>提交历史</Text>
            {commits.map((c) => (
              <View key={c.hash} style={{ flexDirection: 'row', alignItems: 'flex-start', gap: 10, paddingVertical: 9, borderBottomWidth: StyleSheet.hairlineWidth, borderColor: t.line }}>
                <View style={{ width: 22, height: 22, borderRadius: 99, borderWidth: 2, borderColor: t.ac, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 8, color: t.ac, fontFamily: 'monospace' }}>{c.hash.slice(0, 5)}</Text></View>
                <View style={{ flex: 1, minWidth: 0 }}>
                  <Text numberOfLines={1} style={{ fontSize: 13, fontWeight: '500', color: t.tx }}>{c.message}</Text>
                  <Text style={{ fontSize: 11, color: t.tx3, marginTop: 1 }}>{c.author} · {new Date(c.date).toLocaleString()}</Text>
                </View>
              </View>
            ))}
          </View>
        )}
      </ScrollView>

      <Modal visible={commitModal} transparent animationType="fade" onRequestClose={() => setCommitModal(false)}>
        <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'center', padding: 24 }}>
          <View style={{ backgroundColor: t.bg2, borderRadius: 18, padding: 18, ...t.shLift }}>
            <Text style={{ fontSize: 16, fontWeight: '700', color: t.tx, marginBottom: 12 }}>提交变更</Text>
            <TextInput multiline value={msg} onChangeText={setMsg} placeholder="提交信息…" placeholderTextColor={t.tx3}
              style={{ minHeight: 90, maxHeight: 160, backgroundColor: t.bg3, borderRadius: 12, padding: 12, color: t.tx, fontSize: 14, textAlignVertical: 'top' }} />
            <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
              <Pressable onPress={() => setCommitModal(false)} style={{ flex: 1, height: 44, borderRadius: 12, backgroundColor: t.bg3, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 14, fontWeight: '600', color: t.tx2 }}>取消</Text></Pressable>
              <Pressable onPress={() => void doCommit()} style={{ flex: 1, height: 44, borderRadius: 12, backgroundColor: t.ac, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 14, fontWeight: '700', color: t.acInk }}>提交</Text></Pressable>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

function statusLabel(s: GitFileStatus['status']): string {
  return { added: '新增', modified: '修改', deleted: '删除', untracked: '未跟踪', renamed: '重命名' }[s] || s;
}