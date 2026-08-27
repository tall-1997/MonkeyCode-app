/**
 * 本地 Agent 会话 —— 复用云端/自有模型配置，通过本地引擎（EngineBridge）执行任务。
 * 对标 Desktop Windows 本地版 + OpenMinis/shiyi 的本地工作台。
 *  - 选择模型（来自 listModels 的私有/云端模型）
 *  - 输入任务 → 本地 Agent 引擎（AgentRuntime）执行
 *  - 帧流实时展示（assistant 文本 / 工具调用 / 结果 / 完成）
 *  - 工作目录：默认 App 工作区（PRoot 沙箱 /workspace）
 */
import { useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, KeyboardAvoidingView, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { PrivilegedBanner } from '@/components/PrivilegedBanner';
import { Card, GlassNav, PickerSheet, type PickerOption } from '@/components/ui';
import { engineBridge, type EngineConfig, type EngineFrame } from '@/local/EngineBridge';
import { buildEngineConfig, loadAgentModels, toAgentPickerOptions, type AgentModelOption } from '@/local/agentBackend';
import { useTheme } from '@/theme';

type Msg = { kind: 'cmd' | 'assistant' | 'thought' | 'tool' | 'toolupd' | 'plan' | 'err' | 'sys' | 'approval' | 'subagent'; text: string; permId?: string; toolName?: string; toolArgs?: string };

export default function LocalAgentScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const scrollRef = useRef<ScrollView>(null);

  const [models, setModels] = useState<AgentModelOption[]>([]);
  const [selectedKey, setSelectedKey] = useState('');
  const [modelPicking, setModelPicking] = useState(false);
  const [workDir, setWorkDir] = useState('/workspace'); // PRoot 沙箱工作目录

  const [cmd, setCmd] = useState('');
  const [history, setHistory] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);

  const load = useCallback(async () => {
    try {
      const ms = await loadAgentModels();
      setModels(ms);
      if (!selectedKey && ms.length > 0) setSelectedKey(ms[0].key);
    } catch (e: any) {
      setHistory((h) => [...h, { kind: 'err', text: `加载模型失败: ${e.message}` }]);
    }
  }, [selectedKey]);

  useEffect(() => { void load(); }, [load]);

  // 启动引擎（选择某个模型）
  const startEngine = useCallback(async (opt: AgentModelOption, initial: string) => {
    setBusy(true);
    setReady(false);
    try {
      const cfg = buildEngineConfig(opt, workDir, initial);
      const sid = await engineBridge.startEngine(cfg);
      setReady(true);
      historyRef.current = [...historyRef.current, { kind: 'sys', text: `引擎就绪（会话 ${sid}）· 模型 ${opt.label}` }];
      setHistory(historyRef.current);
    } catch (e: any) {
      setHistory((h) => [...h, { kind: 'err', text: `引擎启动失败: ${e.message}` }]);
    } finally {
      setBusy(false);
    }
  }, [workDir]);

  const historyRef = useRef<Msg[]>([]);
  useEffect(() => { historyRef.current = history; }, [history]);

  // 帧流订阅
  useEffect(() => {
    const unsub = engineBridge.onFrame((frame: EngineFrame) => {
      const kind = frame.kind;
      const data = frame.data;
      if (frame.type === 'task-started') {
        historyRef.current = [...historyRef.current, { kind: 'sys', text: '任务开始执行' }];
        setHistory(historyRef.current);
        return;
      }
      if (frame.type === 'task-ended') {
        const status = data?.status || 'finished';
        const turns = data?.turns ?? 0;
        historyRef.current = [...historyRef.current, { kind: 'sys', text: `任务${status === 'cancelled' ? '已取消' : '完成'}（${turns} 轮）` }];
        setHistory(historyRef.current);
        setBusy(false);
        setReady(false);
        return;
      }
      if (frame.type === 'task-error') {
        historyRef.current = [...historyRef.current, { kind: 'err', text: data?.message || '任务执行异常' }];
        setHistory(historyRef.current);
        setBusy(false);
        setReady(false);
        return;
      }
      if (frame.type === 'permission-req') {
        const permId = data?.tool_call_id || '';
        const toolName = data?.tool_name || '';
        const toolArgs = data?.params ? JSON.stringify(data.params) : '';
        historyRef.current = [...historyRef.current, { kind: 'approval', text: `请求权限: ${toolName}`, permId, toolName, toolArgs }];
        setHistory(historyRef.current);
        return;
      }
      if (frame.type === 'permission-resolved') {
        const outcome = data?.outcome || 'denied';
        historyRef.current = [...historyRef.current, { kind: 'sys', text: `权限${outcome === 'approved' ? '已批准' : '已拒绝'}` }];
        setHistory(historyRef.current);
        return;
      }
      if (frame.type === 'task-notification') {
        const msg = data?.message || '';
        historyRef.current = [...historyRef.current, { kind: 'subagent', text: `子代理完成: ${msg}` }];
        setHistory(historyRef.current);
        return;
      }
      if (kind === 'acp_event' && data) {
        const dt = data.type;
        if (dt === 'agent_message_chunk') {
          const chunk = data.content || '';
          historyRef.current = appendChunk(historyRef.current, chunk);
          setHistory(historyRef.current);
        } else if (dt === 'agent_thought_chunk') {
          const chunk = data.content || '';
          historyRef.current = appendThought(historyRef.current, chunk);
          setHistory(historyRef.current);
        } else if (dt === 'tool_call') {
          const name = data.name || '';
          const args = data.arguments || '';
          historyRef.current = [...historyRef.current, { kind: 'tool', text: `${name} ${args}` }];
          setHistory(historyRef.current);
        } else if (dt === 'tool_call_update') {
          const status = data.status || 'completed';
          if (status === 'failed') {
            historyRef.current = [...historyRef.current, { kind: 'toolupd', text: `${data.name} 失败: ${truncate(data.error || '')}` }];
          } else if (status === 'progress') {
            historyRef.current = [...historyRef.current, { kind: 'toolupd', text: `${data.name} 进行中...` }];
          } else {
            historyRef.current = [...historyRef.current, { kind: 'toolupd', text: `${data.name} 完成: ${truncate(data.result || '')}` }];
          }
          setHistory(historyRef.current);
        } else if (dt === 'plan') {
          const planText = data.content || data.plan || '';
          historyRef.current = [...historyRef.current, { kind: 'plan', text: planText || '任务规划已生成' }];
          setHistory(historyRef.current);
        } else if (dt === 'subagent_tool' || dt === 'subagent_text' || dt === 'subagent_output' || dt === 'child_session') {
          const subagentName = data.agent_name || data.name || '';
          const subagentText = data.content || data.text || data.output || '';
          historyRef.current = [...historyRef.current, { kind: 'subagent', text: `[${subagentName}] ${subagentText}` }];
          setHistory(historyRef.current);
        } else if (dt === 'usage_update') {
          const prompt = data.prompt_tokens ?? 0;
          const completion = data.completion_tokens ?? 0;
          historyRef.current = [...historyRef.current, { kind: 'sys', text: `Token 用量: ${prompt}+${completion}` }];
          setHistory(historyRef.current);
        } else if (dt === 'compact_status') {
          const msg = data.message || '上下文已压缩';
          historyRef.current = [...historyRef.current, { kind: 'sys', text: msg }];
          setHistory(historyRef.current);
        }
      }
      setTimeout(() => scrollRef.current?.scrollToEnd({ animated: true }), 60);
    });
    const unsub2 = engineBridge.onError((e) => {
      historyRef.current = [...historyRef.current, { kind: 'err', text: e.message }];
      setHistory(historyRef.current);
      setBusy(false);
      setReady(false);
    });
    return () => { unsub(); unsub2(); };
  }, []);

  const run = async () => {
    const c = cmd.trim();
    if (!c || busy) return;
    setCmd('');
    const opt = models.find((m) => m.key === selectedKey);
    if (!opt) { Alert.alert('提示', '请先选择模型'); return; }
    historyRef.current = [...historyRef.current, { kind: 'cmd', text: `$ ${c}` }];
    setHistory(historyRef.current);

    if (!ready) {
      // 首次：启动引擎并注入初始输入；之后通过 steering 注入
      await startEngine(opt, c);
    } else {
      try {
        await engineBridge.sendInput(c);
      } catch (e: any) {
        setHistory((h) => [...h, { kind: 'err', text: e.message }]);
      }
    }
  };

  const handleApproval = (permId: string, approve: boolean) => {
    if (approve) {
      void engineBridge.approvePermission(permId, true);
    } else {
      void engineBridge.denyPermission(permId);
    }
  };

  const stop = async () => {
    setBusy(false);
    setReady(false);
    await engineBridge.cancelTask();
    setHistory((h) => [...h, { kind: 'sys', text: '已取消' }]);
  };

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    void engineBridge.stopEngine();
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  const pickOptions: PickerOption[] = toAgentPickerOptions(models);

  return (
    <View style={{ flex: 1, backgroundColor: t.termBg }}>
      <GlassNav title="本地 Agent" onBack={leave} right={
        ready || busy ? (
          <Pressable onPress={stop} hitSlop={8} style={{ padding: 8 }}>
            <Icons.stop size={20} color={t.red} sw={2} />
          </Pressable>
        ) : undefined
      } />
      <PrivilegedBanner />
      <KeyboardAvoidingView style={{ flex: 1 }} behavior="padding">
        <ScrollView ref={scrollRef} style={{ flex: 1 }} contentContainerStyle={{ paddingTop: 56, paddingBottom: insets.bottom + 12, paddingHorizontal: 12 }}>
          {/* 模型选择 */}
          <Pressable onPress={() => { if (!busy) setModelPicking(true); }} style={({ pressed }) => [{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4, paddingHorizontal: 12, paddingVertical: 10, borderRadius: 12, backgroundColor: t.bg3 }, pressed && { opacity: 0.7 }]}>
            <Icons.brain size={15} color={t.acTx} sw={1.8} />
            <Text numberOfLines={1} style={{ flex: 1, fontSize: 13, color: t.tx, fontWeight: '600' }}>
              {models.find((m) => m.key === selectedKey)?.label || '选择模型'}
            </Text>
            <Icons.chevron size={14} color={t.tx3} sw={1.9} />
          </Pressable>
          <Text style={{ fontSize: 11, color: t.tx3, marginTop: 6, marginBottom: 2 }}>
            工作目录：{workDir}（PRoot 沙箱，可运行 git/python 等）
          </Text>

          {history.length === 0 && (
            <Text style={{ fontSize: 12.5, color: t.tx3, fontFamily: 'monospace', lineHeight: 20, marginTop: 8 }}>
              本地 Agent · 复用所选模型配置\n在设备上执行任务（文件/命令/系统操作）。\n输入任务后按发送开始（首次将启动引擎）。
            </Text>
          )}

          {history.map((line, i) => {
            if (line.kind === 'approval') {
              return (
                <View key={i} style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 6, paddingVertical: 8, paddingHorizontal: 12, borderRadius: 10, backgroundColor: t.acGhost }}>
                  <Text style={{ flex: 1, fontFamily: 'monospace', fontSize: 12, color: t.acTx }}>{line.text}</Text>
                  <Pressable onPress={() => handleApproval(line.permId || '', true)} style={{ paddingHorizontal: 10, paddingVertical: 4, borderRadius: 6, backgroundColor: t.add }}>
                    <Text style={{ fontSize: 12, fontWeight: '700', color: '#fff' }}>批准</Text>
                  </Pressable>
                  <Pressable onPress={() => handleApproval(line.permId || '', false)} style={{ paddingHorizontal: 10, paddingVertical: 4, borderRadius: 6, backgroundColor: t.red }}>
                    <Text style={{ fontSize: 12, fontWeight: '700', color: '#fff' }}>拒绝</Text>
                  </Pressable>
                </View>
              );
            }
            return (
              <Text key={i} selectable style={{
                fontFamily: 'monospace', fontSize: 12.5, lineHeight: 19, marginTop: 2,
                color: line.kind === 'cmd' ? t.termAcc
                  : line.kind === 'tool' ? '#7aa2f7'
                  : line.kind === 'toolupd' ? '#9ece6a'
                  : line.kind === 'thought' ? t.tx3
                  : line.kind === 'plan' ? '#e0af68'
                  : line.kind === 'subagent' ? '#bb9af7'
                  : line.kind === 'err' ? t.red
                  : line.kind === 'sys' ? t.tx3
                  : t.termTx,
              }}>{line.text}</Text>
            );
          })}
          {busy && !ready && <Text style={{ color: t.acTx, fontSize: 12, marginTop: 6 }}>引擎启动中…</Text>}
          {busy && ready && <Text style={{ color: t.tx3, fontSize: 12, marginTop: 4 }}>执行中…</Text>}
        </ScrollView>

        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 12, paddingTop: 6, borderTopWidth: 1, borderColor: 'rgba(255,255,255,0.08)' }}>
          <TextInput
            value={cmd}
            onChangeText={setCmd}
            placeholder={selectedKey ? '输入任务…' : '请先选择模型'}
            placeholderTextColor={t.tx3}
            editable={!!selectedKey && !busy}
            onSubmitEditing={() => void run()}
            autoCapitalize="none"
            autoCorrect={false}
            style={{ flex: 1, height: 42, borderRadius: 10, paddingHorizontal: 12, backgroundColor: t.bg3, color: t.termTx }}
          />
          <Pressable onPress={() => void run()} disabled={!selectedKey || busy} style={({ }) => ({ width: 42, height: 42, borderRadius: 12, backgroundColor: selectedKey ? t.ac : t.bg3, alignItems: 'center', justifyContent: 'center', opacity: selectedKey ? 1 : 0.4 })}>
            <Icons.send size={18} color={selectedKey ? t.acInk : t.tx3} sw={2.2} />
          </Pressable>
        </View>
      </KeyboardAvoidingView>

      <PickerSheet
        title="选择本地 Agent 模型" visible={modelPicking} options={pickOptions} selected={selectedKey}
        onPick={(k) => setSelectedKey(k)} onClose={() => setModelPicking(false)}
      />
    </View>
  );
}

function appendThought(hist: Msg[], chunk: string): Msg[] {
  if (hist.length === 0) return [...hist, { kind: 'thought', text: chunk }];
  const last = hist[hist.length - 1];
  if (last.kind === 'thought') {
    const updated = { ...last, text: last.text + chunk };
    return [...hist.slice(0, -1), updated];
  }
  return [...hist, { kind: 'thought', text: chunk }];
}

function appendChunk(hist: Msg[], chunk: string): Msg[] {
  if (hist.length === 0) return [...hist, { kind: 'assistant', text: chunk }];
  const last = hist[hist.length - 1];
  if (last.kind === 'assistant') {
    const updated = { ...last, text: last.text + chunk };
    return [...hist.slice(0, -1), updated];
  }
  return [...hist, { kind: 'assistant', text: chunk }];
}

function truncate(s: string, n = 300): string {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}