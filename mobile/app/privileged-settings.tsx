/**
 * 特权能力设置 —— Root + LSPosed 可用时的完整能力开关（对齐 Eta 的独立开关策略）。
 * 沙箱模式下所有开关禁用并提示。
 */
import { useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, NativeEventEmitter, NativeModules, Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { PrivilegedBanner } from '@/components/PrivilegedBanner';
import { Card, GlassNav, LoadingView, PrimaryButton, Row } from '@/components/ui';
import { loadGovernorConfig, privilegedApi, saveGovernorConfig, type GovernorConfig } from '@/local/privilegedApi';
import { useTheme, type Theme } from '@/theme';
import { permissionDetector } from '@/local/PermissionDetector';

function SwitchRow({ label, sub, value, onValueChange, disabled, t, divider }: {
  label: string; sub?: string; value: boolean; onValueChange: (v: boolean) => void; disabled?: boolean; t: Theme; divider?: boolean;
}) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13, paddingHorizontal: 16, borderTopWidth: divider ? StyleSheet.hairlineWidth : 0, borderColor: t.line, opacity: disabled ? 0.4 : 1 }}>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>{label}</Text>
        {sub ? <Text style={{ fontSize: 11.5, color: t.tx3, marginTop: 2, lineHeight: 16 }}>{sub}</Text> : null}
      </View>
      <Switch value={value && !disabled} onValueChange={onValueChange} disabled={disabled}
        trackColor={{ false: t.bg4, true: t.acGhost }} thumbColor={value && !disabled ? t.ac : t.tx3} ios_backgroundColor={t.bg4} />
    </View>
  );
}

export default function PrivilegedSettingsScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();

  const [cfg, setCfg] = useState<GovernorConfig | null>(null);
  const [privileged, setPrivileged] = useState(false);
  const [deviceStatus, setDeviceStatus] = useState<{ battery?: number; storageAvailableMB?: number; memoryAvailableMB?: number; wifiEnabled?: boolean } | null>(null);
  const [alpineInstalled, setAlpineInstalled] = useState(false);
  const [alpineBusy, setAlpineBusy] = useState(false);
  const [alpineProgress, setAlpineProgress] = useState(0);
  // PRoot 免 root Linux 沙箱：只要 Android + 原生模块存在即可用，不依赖 Root
  const [linuxAvailable, setLinuxAvailable] = useState(false);

  useEffect(() => {
    let active = true;
    void (async () => {
      setPrivileged(permissionDetector.isPrivileged());
      const st = permissionDetector.getState();
      const detected = st ?? await permissionDetector.detect();
      if (active) {
        setPrivileged(detected.mode === 'privileged');
        setLinuxAvailable(detected.capabilities.alpineLinux || detected.capabilities.fileSystem);
      }
      const c = await loadGovernorConfig();
      if (active) setCfg(c);
      if (active) setAlpineInstalled(await privilegedApi.isAlpineInstalled());
    })();
    return () => { active = false; };
  }, []);

  // 监听原生 "alpineInstallProgress" 进度事件（installAlpineEnvironment 广播，RN 自动注入 Promise）
  useEffect(() => {
    const mod = NativeModules.PrivilegedExecution;
    if (!mod) return;
    const emitter = new NativeEventEmitter(mod);
    const sub = emitter.addListener('alpineInstallProgress', (e: { progress?: number }) => {
      if (typeof e?.progress === 'number') setAlpineProgress(e.progress);
    });
    return () => { sub.remove(); };
  }, []);

  const refreshStatus = useCallback(async () => {
    if (!privileged) return;
    try {
      const st = await privilegedApi.getDeviceStatus();
      setDeviceStatus({
        battery: st.battery,
        storageAvailableMB: Math.round(st.storageAvailableMB),
        memoryAvailableMB: Math.round(st.memoryAvailableMB),
        wifiEnabled: st.wifiEnabled,
      });
    } catch { /* 忽略 */ }
  }, [privileged]);

  useEffect(() => { if (privileged) void refreshStatus(); }, [privileged, refreshStatus]);

  const set = useCallback((patch: Partial<GovernorConfig>) => {
    setCfg((prev) => {
      const next = { ...prev!, ...patch };
      void saveGovernorConfig(next);
      return next;
    });
  }, []);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  if (!cfg) return <LoadingView label="加载中…" />;

  const installAlpine = () => {
    if (alpineBusy) return;
    Alert.alert('安装 Linux 工具环境', '将下载并安装 Alpine Linux 环境（约 100MB 以内，内含 Git、Python、rg、fd 等工具）。确定继续？', [
      { text: '取消', style: 'cancel' },
      {
        text: '开始安装',
        onPress: () => {
          setAlpineBusy(true);
          setAlpineProgress(0);
          // 不传回调参数：原生 installAlpineEnvironment(promise) 由 RN 自动注入 Promise，
          // 进度经 alpineInstallProgress 事件更新（见上方监听）。避免 bridge 参数冲突白屏。
          privilegedApi.installAlpine()
            .then(() => { setAlpineBusy(false); setAlpineInstalled(true); Alert.alert('安装完成', 'Linux 工具环境已就绪。'); })
            .catch((e: Error) => { setAlpineBusy(false); Alert.alert('安装失败', e.message); });
        },
      },
    ]);
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="本地能力" onBack={leave} />
      <PrivilegedBanner onPress={() => { void refreshStatus(); }} />
      <ScrollView contentContainerStyle={{ paddingTop: 90, paddingBottom: insets.bottom + 40 }}>
        <Card style={{ marginHorizontal: 14, marginTop: 10, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>设备直达</Text>
          <SwitchRow
            label="系统 API 直达"
            sub="闹钟、媒体、音量、Wi-Fi 等系统能力"
            value={cfg.deviceToolsEnabled} onValueChange={(v) => set({ deviceToolsEnabled: v })}
            disabled={!privileged} t={t}
          />
          <SwitchRow
            label="敏感信息读取"
            sub="相册、日历、短信、通知、应用活动"
            value={cfg.sensitiveDataEnabled} onValueChange={(v) => set({ sensitiveDataEnabled: v })}
            disabled={!privileged} t={t} divider
          />
          <SwitchRow
            label="敏感设备操作"
            sub="需要更高权限的设备级操作"
            value={cfg.sensitiveOpsEnabled} onValueChange={(v) => set({ sensitiveOpsEnabled: v })}
            disabled={!privileged} t={t} divider
          />
        </Card>

        <Card style={{ marginHorizontal: 14, marginTop: 12, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>终端与文件</Text>
          <SwitchRow
            label="终端 / 文件工具"
            sub="执行 shell 命令、读写文件"
            value={cfg.terminalEnabled} onValueChange={(v) => set({ terminalEnabled: v })}
            disabled={!privileged} t={t}
          />
          <Row
            icon="shield" label="默认终端身份" value={cfg.terminalIdentity === 'root' ? 'root' : 'user'}
            onPress={privileged ? () => set({ terminalIdentity: cfg.terminalIdentity === 'root' ? 'user' : 'root' }) : undefined}
            divider
          />
          <SwitchRow
            label="GUI 操作"
            sub="截图、无障碍节点、点击、滚动"
            value={cfg.guiAgentEnabled} onValueChange={(v) => set({ guiAgentEnabled: v })}
            disabled={!privileged} t={t} divider
          />
        </Card>

        <Card style={{ marginHorizontal: 14, marginTop: 12, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>系统 Hook（LSPosed）</Text>
          <SwitchRow
            label="系统助手接管"
            sub="电源键 / 系统助手入口唤起（需 LSPosed 激活）"
            value={cfg.systemHookEnabled} onValueChange={(v) => set({ systemHookEnabled: v })}
            disabled={!privileged} t={t}
          />
        </Card>

        <Card style={{ marginHorizontal: 14, marginTop: 12, padding: 16 }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 10 }}>Linux 工具环境 (Alpine)</Text>
          <Text style={{ fontSize: 13, color: t.tx2, lineHeight: 19, marginBottom: 14 }}>
            {alpineInstalled ? '已安装 · 可在终端中选择 linux 环境使用 Git、Python、rg、fd 等工具'
              : '未安装 · 安装后可获得完整 Linux 开发工具链（PRoot 免 root，沙箱模式即可用）'}
          </Text>
          {alpineBusy ? (
            <View style={{ gap: 8 }}>
              <View style={{ height: 6, borderRadius: 99, backgroundColor: t.track, overflow: 'hidden' }}>
                <View style={{ height: '100%', width: `${Math.min(100, alpineProgress * 100)}%`, backgroundColor: t.ac }} />
              </View>
              <Text style={{ fontSize: 12, color: t.tx3, textAlign: 'center' }}>{Math.round(alpineProgress * 100)}%</Text>
            </View>
          ) : (
            <PrimaryButton label={alpineInstalled ? '重新安装' : '安装 Linux 环境'} icon="download" onPress={installAlpine} disabled={!linuxAvailable} block />
          )}
        </Card>

        {privileged && (
          <Card style={{ marginHorizontal: 14, marginTop: 12, padding: 16 }}>
            <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 12 }}>设备状态</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
              <StatusChip label="电量" value={deviceStatus?.battery != null ? `${deviceStatus.battery}%` : '—'} icon="dot" t={t} />
              <StatusChip label="可用存储" value={deviceStatus?.storageAvailableMB != null ? `${deviceStatus.storageAvailableMB} MB` : '—'} icon="folder" t={t} />
              <StatusChip label="可用内存" value={deviceStatus?.memoryAvailableMB != null ? `${deviceStatus.memoryAvailableMB} MB` : '—'} icon="cube" t={t} />
              <StatusChip label="Wi-Fi" value={deviceStatus?.wifiEnabled === true ? '开' : deviceStatus === null ? '—' : '关'} icon="globe" t={t} />
            </View>
          </Card>
        )}
      </ScrollView>
    </View>
  );
}

function StatusChip({ label, value, icon, t }: { label: string; value: string; icon: string; t: Theme }) {
  const I = Icons[icon] ?? Icons.dot;
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 7, paddingHorizontal: 12, paddingVertical: 8, borderRadius: 11, backgroundColor: t.bg3 }}>
      <I size={14} color={t.tx2} sw={1.8} />
      <Text style={{ fontSize: 12, color: t.tx3 }}>{label}</Text>
      <Text style={{ fontSize: 12, fontWeight: '600', color: t.tx }}>{value}</Text>
    </View>
  );
}