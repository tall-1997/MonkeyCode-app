/**
 * 本地终端 —— 特权模式下执行 shell 命令。
 * 支持 android / linux（Alpine）环境切换，user / root 身份切换，命令历史。
 */
import { useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useRef, useState } from 'react';
import { Alert, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { PrivilegedBanner } from '@/components/PrivilegedBanner';
import { Card, GlassNav, PickerSheet, type PickerOption } from '@/components/ui';
import { privilegedApi } from '@/local/privilegedApi';
import { permissionDetector } from '@/local/PermissionDetector';
import { useTheme, type Theme } from '@/theme';

interface TermLine { kind: 'cmd' | 'out' | 'err'; text: string }

export default function LocalTerminalScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();

  const [env, setEnv] = useState<'android' | 'linux'>('android');
  const [identity, setIdentity] = useState<'user' | 'root'>('user');
  const [cmd, setCmd] = useState('');
  const [history, setHistory] = useState<TermLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [envPick, setEnvPick] = useState(false);
  const scrollRef = useRef<ScrollView>(null);

  const privileged = permissionDetector.isPrivileged();

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  const run = async () => {
    const c = cmd.trim();
    if (!c || busy) return;
    setCmd('');
    setHistory((h) => [...h, { kind: 'cmd', text: `$ ${c}` }]);
    setBusy(true);
    try {
      const res = env === 'linux'
        ? await privilegedApi.execAlpine(c)
        : await privilegedApi.execCommand(c, identity);
      const outs: TermLine[] = [];
      if (res.stdout) outs.push({ kind: 'out' as const, text: res.stdout.trimEnd() });
      if (res.stderr) outs.push({ kind: 'err' as const, text: res.stderr.trimEnd() });
      setHistory((h) => [...h, ...outs, { kind: 'out', text: res.exitCode === 0 ? '' : `退出码 ${res.exitCode}` }]);
      setTimeout(() => scrollRef.current?.scrollToEnd({ animated: true }), 60);
    } catch (e: any) {
      setHistory((h) => [...h, { kind: 'err', text: e.message || '执行失败' }]);
    } finally {
      setBusy(false);
    }
  };

  const envOptions: PickerOption[] = [
    { key: 'android', title: 'Android Shell', sub: '系统原生 shell' },
    { key: 'linux', title: 'Alpine Linux', sub: '需先安装 Linux 工具环境' },
  ];

  return (
    <View style={{ flex: 1, backgroundColor: t.termBg }}>
      <GlassNav title="本地终端" onBack={leave} right={
        <Pressable onPress={() => setEnvPick(true)} style={{ flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 99, backgroundColor: t.bg3 }}>
          <Text style={{ fontSize: 12, fontWeight: '600', color: t.tx2 }}>{env === 'linux' ? 'linux' : 'android'}</Text>
          <Icons.chevron size={13} color={t.tx2} sw={1.9} />
        </Pressable>
      } />
      <PrivilegedBanner />
      <KeyboardAvoidingView style={{ flex: 1 }} behavior="padding">
        <ScrollView ref={scrollRef} style={{ flex: 1 }} contentContainerStyle={{ paddingTop: 56, paddingBottom: insets.bottom + 12, paddingHorizontal: 12 }}>
          {!privileged ? (
            <Card style={{ padding: 16, marginTop: 4 }}>
              <Text style={{ fontSize: 13, color: t.tx2, lineHeight: 20 }}>终端需要特权模式（Root）。当前为沙箱模式，仅可尝试访问受限命令。</Text>
            </Card>
          ) : null}

          {history.length === 0 && (
            <Text style={{ fontSize: 12.5, color: t.tx3, fontFamily: 'monospace', lineHeight: 20, marginTop: 6 }}>
              {env === 'linux' ? 'Alpine Linux shell @ /workspace' : 'Android shell'}（{identity}）\n输入命令后按发送执行。
            </Text>
          )}

          {history.map((line, i) => (
            <Text key={i} selectable style={{
              fontFamily: 'monospace', fontSize: 12.5, lineHeight: 19,
              color: line.kind === 'cmd' ? t.termAcc : line.kind === 'err' ? t.red : t.termTx,
              opacity: line.text === '' ? 0 : 1,
            }}>{line.text === '' ? ' ' : line.text}</Text>
          ))}

          {busy && <Text style={{ color: t.tx3, fontSize: 12, fontFamily: 'monospace', marginTop: 4 }}>…运行中</Text>}
        </ScrollView>

        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 12, paddingTop: 6, borderTopWidth: 1, borderColor: 'rgba(255,255,255,0.08)' }}>
          <Pressable onPress={() => setIdentity((v) => (v === 'root' ? 'user' : 'root'))} disabled={!privileged} style={{ paddingHorizontal: 8, paddingVertical: 6, borderRadius: 8, backgroundColor: identity === 'root' ? t.redGhost : t.bg3, opacity: privileged ? 1 : 0.4 }}>
            <Text style={{ fontSize: 12, fontWeight: '700', color: identity === 'root' ? t.red : t.tx2 }}>#{identity === 'root' ? 'root' : 'user'}</Text>
          </Pressable>
          <TextInput
            value={cmd}
            onChangeText={setCmd}
            placeholder={privileged ? '$ 输入命令' : '终端不可用'}
            placeholderTextColor={t.tx3}
            editable={privileged && !busy}
            onSubmitEditing={() => void run()}
            autoCapitalize="none"
            autoCorrect={false}
            style={{ flex: 1, height: 42, borderRadius: 10, paddingHorizontal: 12, backgroundColor: t.bg3, color: t.termTx, fontFamily: 'monospace' }}
          />
          <Pressable onPress={() => void run()} disabled={!privileged || busy} style={({ }) => ({ width: 42, height: 42, borderRadius: 12, backgroundColor: privileged ? t.ac : t.bg3, alignItems: 'center', justifyContent: 'center', opacity: privileged ? 1 : 0.4 })}>
            <Icons.send size={18} color={privileged ? t.acInk : t.tx3} sw={2.2} />
          </Pressable>
        </View>
      </KeyboardAvoidingView>

      <PickerSheet
        title="终端环境" visible={envPick} options={envOptions} selected={env}
        onPick={(k) => setEnv(k as 'android' | 'linux')} onClose={() => setEnvPick(false)}
      />
    </View>
  );
}