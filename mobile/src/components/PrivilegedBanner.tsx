import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { permissionDetector, PermissionState } from '@/local/PermissionDetector';
import { Icons } from '@/components/Icons';
import { useTheme } from '@/theme';

/** 顶部权限状态条：特权模式（Root + LSPosed）或沙箱模式指示，点击可跳转设置。 */
export function PrivilegedBanner({ onPress, state }: { onPress?: () => void; state?: PermissionState | null }) {
  const t = useTheme();
  const [cur, setCur] = React.useState<PermissionState | null>(state ?? permissionDetector.getState());
  React.useEffect(() => {
    if (state === undefined) {
      permissionDetector.detect().then(setCur);
      const unsub = permissionDetector.addListener(setCur);
      return unsub;
    }
  }, [state]);

  const privileged = cur?.mode === 'privileged';
  const color = privileged ? t.add : t.tx3;
  const Icon = privileged ? Icons.shield : Icons.server;
  const text = privileged ? `特权模式 · ${rootLabel(cur?.root)}` : '沙箱模式 · 无 Root 权限';

  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.banner, { backgroundColor: privileged ? t.bg2 : t.bg3 }, pressed && { opacity: 0.7 }]}>
      <Icon size={13} color={color} sw={1.8} />
      <Text style={{ color: privileged ? t.add : t.tx3, fontSize: 11.5, fontWeight: '600' }}>{text}</Text>
      {onPress ? <Icons.chevron size={13} color={t.tx3} sw={1.8} style={{ marginLeft: 'auto' }} /> : null}
    </Pressable>
  );
}

function rootLabel(root: PermissionState['root'] | undefined): string {
  if (!root?.available) return '无';
  switch (root.manager) {
    case 'magisk': return 'Magisk';
    case 'kernelsu': return 'KernelSU';
    case 'apatch': return 'APatch';
    default: return 'Root';
  }
}

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    marginHorizontal: 14,
    marginTop: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 12,
  },
});

export default PrivilegedBanner;