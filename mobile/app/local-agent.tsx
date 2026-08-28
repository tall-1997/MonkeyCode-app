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
import { createLocalAgentSession, finishLocalAgentSession, getLocalSession } from '@/local/localProjects';
import { appendEngineFrame, type LocalAgentMessage } from '@/local/frameProtocol';
import { useTheme } from '@/theme';

type Msg = LocalAgentMessage;

export default function LocalAgentScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const params = useLocalSearchParams<{ prompt?: string; sessionId?: string; engineId?: string }>();
  const scrollRef = useRef<ScrollView>(null);

  const [models, setModels] = useState<AgentModelOption[]>([]);
  const [selectedKey, setSelectedKey] = useState('');
  const [modelPicking, setModelPicking] = useState(false);
  const [workDir] = useState('/workspace'); // PRoot 沙箱工作目录

  const [cmd, setCmd] = useState(params.prompt || '');
  const [history, setHistory] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [replaying, setReplaying] = useState(!!params.sessionId);
  const historical = !!params.sessionId;
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

  useEffect(() => { if (!historical) void load(); }, [historical, load]);

  useEffect(() => {
    if (!params.sessionId) return;
    let active = true;
    void (async () => {
      try {
        const local = await getLocalSession(params.sessionId!);
        const engineId = params.engineId || local?.engineId;
        if (!engineId) throw new Error('该会话缺少引擎记录');
        let window = await engineBridge.openSession(engineId);
        let frames = [...window.frames];
        while (window.has_more) {
          window = await engineBridge.getSessionHistory(engineId, window.cursor, 100);
          frames = frames.concat(window.frames);
        }
        if (!active) return;
        historyRef.current = frames.reduce<Msg[]>((messages, frame) => appendEngineFrame(messages, frame), []);
        setHistory(historyRef.current);
      } catch (e: any) {
        if (active) setHistory([{ kind: 'err', text: `恢复会话失败: ${e.message}` }]);
      } finally {
        if (active) setReplaying(false);
      }
    })();
    return () => { active = false; };
  }, [params.engineId, params.sessionId]);

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
      const data = frame.data;
      if (frame.type === 'task-ended') {
        const status = data?.status || 'finished';
        setBusy(false);
        setReady(false);
        if (sessionIdRef.current) void finishLocalAgentSession(sessionIdRef.current, status === 'cancelled' ? 'cancelled' : status === 'error' ? 'error' : 'finished');
      }
      if (frame.type === 'task-error') {
        setBusy(false);
        setReady(false);
        if (sessionIdRef.current) void finishLocalAgentSession(sessionIdRef.current, 'error');
      }
      historyRef.current = appendEngineFrame(historyRef.current, frame);
      setHistory(historyRef.current);
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
    if (!historical) {
      await engineBridge.stopEngine();
      if (sessionIdRef.current) await finishLocalAgentSession(sessionIdRef.current, 'cancelled');
    }
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [historical, navigation, router]);

  const pickOptions: PickerOption[] = toAgentPickerOptions(models);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title={historical ? '会话记录' : '本地 Agent'} onBack={leave} right={
        ready || busy ? (
          <Pressable onPress={stop} hitSlop={8} style={{ padding: 8 }}>
            <Icons.stop size={20} color={t.red} sw={2} />
          </Pressable>
        ) : undefined
      } />
      <KeyboardAvoidingView style={{ flex: 1 }} behavior="padding">
        <ScrollView ref={scrollRef} style={{ flex: 1 }} contentContainerStyle={{ paddingTop: insets.top + 64, paddingBottom: insets.bottom + 12, paddingHorizontal: 12 }}>
          <PrivilegedBanner />
          {historical ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4, paddingHorizontal: 12, paddingVertical: 10, borderRadius: 12, backgroundColor: t.bg3 }}>
              <Icons.clock size={15} color={t.tx3} />
              <Text style={{ color: t.tx2, fontSize: 13, fontWeight: '600' }}>本机保存的只读会话记录</Text>
            </View>
          ) : (
            <>
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
            </>
          )}

          {replaying ? <Text style={{ color: t.acTx, fontSize: 12, marginTop: 10 }}>正在恢复会话记录…</Text> : null}
          {history.length === 0 && !replaying && (
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
                  {!historical ? <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
                    <Pressable onPress={() => handleApproval(line.permId || '', false)} style={{ flex: 1, minHeight: 44, borderRadius: 12, backgroundColor: t.bg3, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 13.5, fontWeight: '700', color: t.red }}>拒绝</Text></Pressable>
                    <Pressable onPress={() => handleApproval(line.permId || '', true)} style={{ flex: 1.4, minHeight: 44, borderRadius: 12, backgroundColor: t.ac, alignItems: 'center', justifyContent: 'center' }}><Text style={{ fontSize: 13.5, fontWeight: '800', color: t.acInk }}>批准本次</Text></Pressable>
                  </View> : null}
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

        {!historical ? <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 12, paddingTop: 6, borderTopWidth: 1, borderColor: 'rgba(255,255,255,0.08)' }}>
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
        </View> : null}
      </KeyboardAvoidingView>

      <PickerSheet
        title="选择本地 Agent 模型" visible={modelPicking} options={pickOptions} selected={selectedKey}
        onPick={(k) => setSelectedKey(k)} onClose={() => setModelPicking(false)}
      />
    </View>
  );
}
