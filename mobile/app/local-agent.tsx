/**
 * 本地 Agent 会话 —— 复用云端/自有模型配置，通过本地引擎（EngineBridge）执行任务。
 * 对标 Desktop Windows 本地版 + OpenMinis/shiyi 的本地工作台。
 *  - 选择模型（来自 listModels 的私有/云端模型）
 *  - 输入任务 → 本地 Agent 引擎（AgentRuntime）执行
 *  - 帧流实时展示（assistant 文本 / 工具调用 / 结果 / 完成）
 *  - 工作目录：默认 App 工作区（PRoot 沙箱 /workspace）
 */
import { useLocalSearchParams, useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, KeyboardAvoidingView, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { PrivilegedBanner } from '@/components/PrivilegedBanner';
import { Card, GlassNav, PickerSheet, type PickerOption } from '@/components/ui';
import { engineBridge, type EngineFrame } from '@/local/EngineBridge';
import { buildEngineConfig, loadAgentModels, toAgentPickerOptions, type AgentModelOption } from '@/local/agentBackend';
import { createLocalAgentSession, finishLocalAgentSession } from '@/local/localProjects';
import { formatFrameValue, frameText, sessionUpdateOf, sessionUpdateType } from '@/local/frameProtocol';
import { useTheme } from '@/theme';

type Msg = { kind: 'cmd' | 'assistant' | 'thought' | 'tool' | 'toolupd' | 'plan' | 'err' | 'sys' | 'approval' | 'subagent'; text: string; permId?: string; toolName?: string; toolArgs?: string };

export default function LocalAgentScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const params = useLocalSearchParams<{ prompt?: string }>();
  const scrollRef = useRef<ScrollView>(null);

  const [models, setModels] = useState<AgentModelOption[]>([]);
  const [selectedKey, setSelectedKey] = useState('');
  const [modelPicking, setModelPicking] = useState(false);
  const [workDir] = useState('/workspace'); // PRoot 沙箱工作目录

  const [cmd, setCmd] = useState(params.prompt || '');
  const [history, setHistory] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const sessionIdRef = useRef('');

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
      sessionIdRef.current = await createLocalAgentSession(initial, sid);
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
        const label = status === 'cancelled' ? '已取消' : status === 'error' ? '异常终止' : '完成';
        historyRef.current = [...historyRef.current, { kind: status === 'error' ? 'err' : 'sys', text: `任务${label}（${turns} 轮）` }];
        setHistory(historyRef.current);
        setBusy(false);
        setReady(false);
        if (sessionIdRef.current) void finishLocalAgentSession(sessionIdRef.current, status === 'cancelled' ? 'cancelled' : status === 'error' ? 'error' : 'finished');
        return;
      }
      if (frame.type === 'task-error') {
        historyRef.current = [...historyRef.current, { kind: 'err', text: data?.error || data?.message || '任务执行异常' }];
        setHistory(historyRef.current);
        setBusy(false);
        setReady(false);
        if (sessionIdRef.current) void finishLocalAgentSession(sessionIdRef.current, 'error');
        return;
      }
      if (frame.type === 'permission-req') {
        const permId = data?.permissionId || data?.id || '';
        const toolName = data?.tool || data?.tool_name || '';
        const toolArgs = data?.arguments
          ? (typeof data.arguments === 'string' ? data.arguments : JSON.stringify(data.arguments))
          : data?.params ? JSON.stringify(data.params) : '';
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
      if (kind === 'acp_event' && data) {
        const update = sessionUpdateOf(data);
        const dt = sessionUpdateType(update);
        if (!update) return;
        if (dt === 'agent_message_chunk') {
          const chunk = frameText(update.content);
          historyRef.current = appendChunk(historyRef.current, chunk);
          setHistory(historyRef.current);
        } else if (dt === 'agent_thought_chunk') {
          const chunk = frameText(update.content);
          historyRef.current = appendThought(historyRef.current, chunk);
          setHistory(historyRef.current);
        } else if (dt === 'tool_call') {
          const name = update.title || update.name || '';
          const args = formatFrameValue(update.rawInput ?? update.arguments);
          historyRef.current = [...historyRef.current, { kind: 'tool', text: `${name} ${args}` }];
          setHistory(historyRef.current);
        } else if (dt === 'tool_call_update') {
          const status = update.status || 'completed';
          const name = update.title || update.name || update.toolCallId || '工具';
          if (status === 'failed') {
            historyRef.current = [...historyRef.current, { kind: 'toolupd', text: `${name} 失败: ${truncate(frameText(update.rawOutput ?? update.error))}` }];
          } else if (status === 'progress') {
            historyRef.current = [...historyRef.current, { kind: 'toolupd', text: `${name} 进行中...` }];
          } else {
            historyRef.current = [...historyRef.current, { kind: 'toolupd', text: `${name} 完成: ${truncate(frameText(update.rawOutput ?? update.result))}` }];
          }
          setHistory(historyRef.current);
        } else if (dt === 'plan') {
          const planText = frameText(update.content ?? update.plan);
          historyRef.current = [...historyRef.current, { kind: 'plan', text: planText || '任务规划已生成' }];
          setHistory(historyRef.current);
        } else if (dt === 'subagent_tool' || dt === 'subagent_text' || dt === 'subagent_output' || dt === 'child_session') {
          const subagentName = update.agent_name || update.name || update.childSessionId || '子代理';
          const subagentText = frameText(update.content ?? update.text ?? update.output);
          historyRef.current = [...historyRef.current, { kind: 'subagent', text: `[${subagentName}] ${subagentText}` }];
          setHistory(historyRef.current);
        } else if (dt === 'task_notification') {
          const message = frameText(update.message ?? update.content);
          historyRef.current = [...historyRef.current, { kind: 'subagent', text: `子代理完成: ${message}` }];
          setHistory(historyRef.current);
        } else if (dt === 'usage_update') {
          const prompt = update.prompt_tokens ?? update.inputTokens ?? 0;
          const completion = update.completion_tokens ?? update.outputTokens ?? 0;
          historyRef.current = [...historyRef.current, { kind: 'sys', text: `Token 用量: ${prompt}+${completion}` }];
          setHistory(historyRef.current);
        } else if (dt === 'compact_status') {
          const msg = frameText(update.message) || (update.status === 'started' ? '正在压缩上下文...' : '上下文已压缩');
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
      void engineBridge.approvePermission(permId, false);
    } else {
      void engineBridge.denyPermission(permId);
    }
  };

  const stop = async () => {
    setBusy(false);
    setReady(false);
    await engineBridge.cancelTask();
    if (sessionIdRef.current) await finishLocalAgentSession(sessionIdRef.current, 'cancelled');
    setHistory((h) => [...h, { kind: 'sys', text: '已取消' }]);
  };

  const leave = useCallback(async () => {
    if (!navigation.isFocused()) return;
    await engineBridge.stopEngine();
    if (sessionIdRef.current) await finishLocalAgentSession(sessionIdRef.current, 'cancelled');
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  const pickOptions: PickerOption[] = toAgentPickerOptions(models);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
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
                <Card key={i} style={{ marginTop: 8, padding: 14, borderWidth: 1, borderColor: t.ac }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}><Icons.shield size={17} color={t.acTx} /><Text style={{ flex: 1, fontSize: 13.5, fontWeight: '800', color: t.acTx }}>需要审批</Text></View>
                  <Text style={{ marginTop: 10, fontFamily: 'monospace', fontSize: 12.5, lineHeight: 19, color: t.tx }}>{line.text}</Text>
                  {line.toolArgs ? <Text numberOfLines={3} style={{ marginTop: 7, fontFamily: 'monospace', fontSize: 11.5, lineHeight: 17, color: t.tx3 }}>{line.toolArgs}</Text> : null}
                  <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
                    <Pressable onPress={() => handleApproval(line.permId || '', false)} style={{ flex: 1, minHeight: 44, borderRadius: 12, backgroundColor: t.bg3, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 13.5, fontWeight: '700', color: t.red }}>拒绝</Text></Pressable>
                    <Pressable onPress={() => handleApproval(line.permId || '', true)} style={{ flex: 1.4, minHeight: 44, borderRadius: 12, backgroundColor: t.ac, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 13.5, fontWeight: '800', color: t.acInk }}>批准本次</Text></Pressable>
                  </View>
                </Card>
              );
            }
            const meta = {
              assistant: ['正文', 'sparkle', t.tx], thought: ['思考', 'brain', t.tx2], plan: ['计划', 'tasks', t.amber],
              tool: ['工具调用', 'terminal', t.acTx], toolupd: ['工具结果', 'checkCircle', t.add], subagent: ['子代理', 'robot', t.acTx],
              cmd: ['你的任务', 'send', t.tx], err: ['错误', 'alert', t.red], sys: ['系统', 'info', t.tx3],
            }[line.kind] as [string, string, string];
            const I = Icons[meta[1]];
            return <Card key={i} style={{ marginTop: 8, padding: 13, borderLeftWidth: 3, borderLeftColor: meta[2] }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 7, marginBottom: 8 }}><I size={15} color={meta[2]} /><Text style={{ color: meta[2], fontSize: 11.5, fontWeight: '800' }}>{meta[0]}</Text></View>
              <Text selectable style={{ color: line.kind === 'assistant' ? t.tx : t.tx2, fontSize: 13, lineHeight: 20, fontFamily: line.kind === 'tool' || line.kind === 'toolupd' ? 'monospace' : undefined }}>{line.text}</Text>
            </Card>;
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
          <Pressable onPress={() => void run()} disabled={!selectedKey || busy} style={{ width: 42, height: 42, borderRadius: 12, backgroundColor: selectedKey ? t.ac : t.bg3, alignItems: 'center', justifyContent: 'center', opacity: selectedKey ? 1 : 0.4 }}>
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
