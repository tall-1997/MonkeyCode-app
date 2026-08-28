/**
 * 沙箱设置页 —— 选择沙箱类型，查看安装状态，管理终端默认环境。
 */
import { useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, DeviceEventEmitter, Pressable, ScrollView, Switch, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { PrivilegedBanner } from '@/components/PrivilegedBanner';
import { Card, GlassNav, PickerSheet, PrimaryButton, type PickerOption } from '@/components/ui';
import { privilegedApi, type UbuntuMirror } from '@/local/privilegedApi';
import { permissionDetector } from '@/local/PermissionDetector';
import { useTheme } from '@/theme';

const KEY_SANDBOX = 'mc.sandboxConfig';

export default function SandboxSettingsScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();

  const [sandboxType, setSandboxType] = useState<'ubuntu' | 'alpine'>('alpine');
  const [alpineInstalled, setAlpineInstalled] = useState(false);
  const [alpineBusy, setAlpineBusy] = useState(false);
  const [alpineProgress, setAlpineProgress] = useState(0);
  const [ubuntuInstalled, setUbuntuInstalled] = useState(false);
  const [ubuntuBusy, setUbuntuBusy] = useState(false);
  const [ubuntuProgress, setUbuntuProgress] = useState(0);
  const [ubuntuStage, setUbuntuStage] = useState('准备安装');
  const [mirrors, setMirrors] = useState<UbuntuMirror[]>([]);
  const [mirrorId, setMirrorId] = useState('official');
  const [mirrorPicking, setMirrorPicking] = useState(false);
  const [terminalEnv, setTerminalEnv] = useState<'android' | 'linux'>('linux');
  const [privileged, setPrivileged] = useState(false);

  useEffect(() => {
    void (async () => {
      setPrivileged(permissionDetector.isPrivileged());
      setAlpineInstalled(await privilegedApi.isAlpineInstalled());
      const [ubuntuStatus, availableMirrors, nativeType] = await Promise.all([
        privilegedApi.getUbuntuStatus().catch(() => ({ installed: false, installing: false, mirrorId: undefined })),
        privilegedApi.getUbuntuMirrors().catch(() => []),
        privilegedApi.getSandboxType().catch(() => 'alpine' as const),
      ]);
      setUbuntuInstalled(ubuntuStatus.installed);
      setUbuntuBusy(ubuntuStatus.installing);
      setMirrors(availableMirrors);
      setMirrorId(ubuntuStatus.mirrorId || availableMirrors[0]?.id || 'official');
      const cfg = await loadSandboxConfig();
      if (cfg) {
        setSandboxType(nativeType || cfg.type || 'alpine');
        setTerminalEnv(cfg.terminalEnv || 'linux');
      } else {
        setSandboxType(nativeType);
      }
    })();
  }, []);

  useEffect(() => {
    const sub = DeviceEventEmitter.addListener('alpineInstallProgress', (e: { progress?: number }) => {
      if (typeof e?.progress === 'number') setAlpineProgress(e.progress);
    });
    const ubuntuSub = DeviceEventEmitter.addListener('ubuntuInstallProgress', (e: { progress?: number; stage?: string }) => {
      if (typeof e?.progress === 'number') setUbuntuProgress(e.progress);
      if (e?.stage) setUbuntuStage(e.stage);
    });
    return () => { sub.remove(); ubuntuSub.remove(); };
  }, []);

  const installAlpine = () => {
    if (alpineBusy) return;
    Alert.alert('安装 Alpine Linux', '将下载并安装 Alpine Linux 环境（约 100MB）。确定继续？', [
      { text: '取消', style: 'cancel' },
      {
        text: '安装',
        onPress: () => {
          setAlpineBusy(true);
          setAlpineProgress(0);
          privilegedApi.installAlpine()
            .then(() => { setAlpineBusy(false); setAlpineInstalled(true); Alert.alert('完成', 'Alpine Linux 已就绪。'); })
            .catch((e: Error) => { setAlpineBusy(false); Alert.alert('错误', e.message); });
        },
      },
    ]);
  };

  const installUbuntu = () => {
    if (ubuntuBusy) return;
    Alert.alert('安装 Ubuntu', '将下载并安装 Ubuntu 24.04 ARM64 环境（约 200MB）。确定继续？', [
      { text: '取消', style: 'cancel' },
      {
        text: '安装',
        onPress: () => {
          setUbuntuBusy(true);
          setUbuntuProgress(0);
          setUbuntuStage('准备安装');
          privilegedApi.installUbuntu()
            .then(() => { setUbuntuBusy(false); setUbuntuInstalled(true); Alert.alert('完成', 'Ubuntu 24.04 已就绪。'); })
            .catch((e: Error) => { setUbuntuBusy(false); Alert.alert('错误', e.message); });
        },
      },
    ]);
  };

  const selectType = useCallback(async (type: 'ubuntu' | 'alpine') => {
    try {
      await privilegedApi.setSandboxType(type);
      setSandboxType(type);
      await saveSandboxConfig({ type, terminalEnv });
    } catch (e: any) {
      Alert.alert('切换失败', e.message);
    }
  }, [terminalEnv]);

  const selectMirror = useCallback(async (id: string) => {
    try {
      await privilegedApi.setUbuntuMirror(id);
      setMirrorId(id);
      setMirrorPicking(false);
    } catch (e: any) {
      Alert.alert('镜像源设置失败', e.message);
    }
  }, []);

  const toggleTerminalEnv = useCallback(async (v: boolean) => {
    const env = v ? 'linux' : 'android';
    setTerminalEnv(env);
    await saveSandboxConfig({ type: sandboxType, terminalEnv: env });
  }, [sandboxType]);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  const anyInstalled = alpineInstalled || ubuntuInstalled;

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="沙箱设置" onBack={leave} />
      <ScrollView contentContainerStyle={{ paddingTop: insets.top + 64, paddingBottom: insets.bottom + 40, paddingHorizontal: 14 }}>
        <PrivilegedBanner />
        <Card style={{ marginTop: 10, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>沙箱类型</Text>
          <Pressable onPress={() => selectType('ubuntu')}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 14, paddingHorizontal: 16, borderTopWidth: 1, borderColor: t.line, backgroundColor: sandboxType === 'ubuntu' ? t.acGhost : 'transparent' }}>
            <View style={{ flex: 1, minWidth: 0 }}>
              <Text style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>Ubuntu</Text>
              <Text style={{ fontSize: 11.5, color: t.tx3, marginTop: 2 }}>Ubuntu 24.04 ARM64（推荐，完整开发工具链）</Text>
            </View>
            {sandboxType === 'ubuntu' ? <Icons.check size={18} color={t.ac} sw={2.4} /> : null}
          </Pressable>
          <Pressable onPress={() => selectType('alpine')}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 14, paddingHorizontal: 16, borderTopWidth: 1, borderColor: t.line, backgroundColor: sandboxType === 'alpine' ? t.acGhost : 'transparent' }}>
            <View style={{ flex: 1, minWidth: 0 }}>
              <Text style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>Alpine</Text>
              <Text style={{ fontSize: 11.5, color: t.tx3, marginTop: 2 }}>Alpine Linux（轻量，约 100MB）</Text>
            </View>
            {sandboxType === 'alpine' ? <Icons.check size={18} color={t.ac} sw={2.4} /> : null}
          </Pressable>
        </Card>

        <Card style={{ marginTop: 12, padding: 16 }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 12 }}>Ubuntu 安装状态</Text>
          <Pressable disabled={ubuntuBusy || mirrors.length === 0} onPress={() => setMirrorPicking(true)} style={{ minHeight: 44, marginBottom: 12, paddingHorizontal: 12, borderRadius: 12, backgroundColor: t.bg3, flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Icons.globe size={16} color={t.acTx} />
            <View style={{ flex: 1 }}><Text style={{ color: t.tx3, fontSize: 11 }}>下载镜像源</Text><Text style={{ color: t.tx, fontSize: 13, fontWeight: '600', marginTop: 2 }}>{mirrors.find((mirror) => mirror.id === mirrorId)?.name || '正在读取'}</Text></View>
            <Icons.chevron size={15} color={t.tx3} />
          </Pressable>
          {ubuntuInstalled ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Icons.checkCircle size={18} color={t.add} sw={2} />
              <Text style={{ fontSize: 14, color: t.add, fontWeight: '600' }}>已安装</Text>
            </View>
          ) : ubuntuBusy ? (
            <View style={{ gap: 8 }}>
              <View style={{ height: 6, borderRadius: 99, backgroundColor: t.track, overflow: 'hidden' }}>
                <View style={{ height: '100%', width: `${Math.min(100, ubuntuProgress * 100)}%`, backgroundColor: t.ac }} />
              </View>
              <Text style={{ fontSize: 12, color: t.tx3, textAlign: 'center' }}>{ubuntuStage} · {Math.round(ubuntuProgress * 100)}%</Text>
            </View>
          ) : (
            <PrimaryButton label="安装 Ubuntu 24.04" icon="download" onPress={installUbuntu} block />
          )}
        </Card>

        <Card style={{ marginTop: 12, padding: 16 }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 12 }}>Alpine 安装状态</Text>
          {alpineInstalled ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Icons.checkCircle size={18} color={t.add} sw={2} />
              <Text style={{ fontSize: 14, color: t.add, fontWeight: '600' }}>已安装</Text>
            </View>
          ) : alpineBusy ? (
            <View style={{ gap: 8 }}>
              <View style={{ height: 6, borderRadius: 99, backgroundColor: t.track, overflow: 'hidden' }}>
                <View style={{ height: '100%', width: `${Math.min(100, alpineProgress * 100)}%`, backgroundColor: t.ac }} />
              </View>
              <Text style={{ fontSize: 12, color: t.tx3, textAlign: 'center' }}>{Math.round(alpineProgress * 100)}%</Text>
            </View>
          ) : (
            <PrimaryButton label="安装 Alpine Linux" icon="download" onPress={installAlpine} block />
          )}
        </Card>

        <Card style={{ marginTop: 12, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>终端默认环境</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13, paddingHorizontal: 16, borderTopWidth: 1, borderColor: t.line }}>
            <View style={{ flex: 1, minWidth: 0 }}>
              <Text style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>默认使用 Linux 环境</Text>
              <Text style={{ fontSize: 11.5, color: t.tx3, marginTop: 2 }}>关闭则默认使用 Android Shell</Text>
            </View>
            <Switch value={terminalEnv === 'linux'} onValueChange={toggleTerminalEnv}
              trackColor={{ false: t.bg4, true: t.acGhost }} thumbColor={terminalEnv === 'linux' ? t.ac : t.tx3} ios_backgroundColor={t.bg4} />
          </View>
        </Card>

        {anyInstalled ? (
          <Card style={{ marginTop: 12, padding: 16 }}>
            <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 8 }}>已安装环境</Text>
            {alpineInstalled ? (
              <Text style={{ fontSize: 13, color: t.tx2, lineHeight: 20 }}>Alpine Linux — 轻量开发环境，含 Git / Python / rg / fd</Text>
            ) : null}
            {ubuntuInstalled ? (
              <Text style={{ fontSize: 13, color: t.tx2, lineHeight: 20, marginTop: alpineInstalled ? 4 : 0 }}>Ubuntu 24.04 ARM64 — 完整开发环境，含 apt 工具链</Text>
            ) : null}
          </Card>
        ) : null}
      </ScrollView>
      <PickerSheet
        title="选择 Ubuntu 镜像源"
        visible={mirrorPicking}
        selected={mirrorId}
        options={mirrors.map<PickerOption>((mirror) => ({ key: mirror.id, title: mirror.name, sub: mirror.url, icon: 'globe' }))}
        onPick={(id) => void selectMirror(id)}
        onClose={() => setMirrorPicking(false)}
      />
    </View>
  );
}

async function loadSandboxConfig(): Promise<{ type: 'ubuntu' | 'alpine'; terminalEnv: 'android' | 'linux' } | null> {
  try {
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    const raw = await AsyncStorage.getItem(KEY_SANDBOX);
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

async function saveSandboxConfig(cfg: { type: 'ubuntu' | 'alpine'; terminalEnv: 'android' | 'linux' }): Promise<void> {
  try {
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    await AsyncStorage.setItem(KEY_SANDBOX, JSON.stringify(cfg));
  } catch { /* ignore */ }
}
