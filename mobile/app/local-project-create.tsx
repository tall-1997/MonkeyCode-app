/**
 * 新建本地项目 —— 特权模式下创建本地工作区目录（可选初始化 Git 或从远程克隆）。
 * 需要 Root 提权来创建任意路径目录；核心操作走特权 API。
 */
import { useLocalSearchParams, useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { Card, GlassNav, PrimaryButton } from '@/components/ui';
import { createLocalProject } from '@/local/localProjects';
import { privilegedApi } from '@/local/privilegedApi';
import { useTheme } from '@/theme';

const DEFAULT_PATH = '/sdcard/MonkeyCode';
const shellQuote = (value: string) => `'${value.replace(/'/g, `'"'"'`)}'`;

export default function LocalProjectCreateScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const params = useLocalSearchParams<{ mode?: string }>();

  const [name, setName] = useState('');
  const [path, setPath] = useState(DEFAULT_PATH);
  const [remoteUrl, setRemoteUrl] = useState('');
  const [gitInit, setGitInit] = useState(true);
  const [saving, setSaving] = useState(false);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/local-projects');
  }, [navigation, router]);

  const create = async () => {
    const trimmedName = name.trim();
    if (!trimmedName) { Alert.alert('提示', '请输入项目名称'); return; }
    if (!path.trim()) { Alert.alert('提示', '请输入项目路径'); return; }
    if (params.mode === 'clone' && !remoteUrl.trim()) { Alert.alert('提示', '请输入 Git 仓库地址'); return; }

    setSaving(true);
    try {
      // 创建目录（特权模式用 Root；沙箱降级为本地记录）
      try {
        await privilegedApi.createDirectory(path.trim());
      } catch {
        // 目录已存在或无法创建时不阻塞
      }

      if (remoteUrl.trim()) {
        // 克隆远程仓库（需要 git，通常通过 Alpine linux 环境；此处尝试 sh git，失败仅提示）
        try {
          await privilegedApi.execAlpine(`git clone -- ${shellQuote(remoteUrl.trim())} ${shellQuote(path.trim())}`);
        } catch (e: any) {
          Alert.alert('克隆失败', `${e.message || '无法克隆远程仓库'}（项目记录仍将创建）`);
        }
      } else if (gitInit) {
        try {
          await privilegedApi.execAlpine(`git init -- ${shellQuote(path.trim())}`);
        } catch { /* 忽略 */ }
      }

      await createLocalProject(trimmedName, path.trim(), remoteUrl.trim() || undefined);
      Alert.alert('创建成功', `本地项目「${trimmedName}」已创建。`, [{ text: '好', onPress: leave }]);
    } catch (e: any) {
      Alert.alert('创建失败', e.message || '未知错误');
    } finally {
      setSaving(false);
    }
  };

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="新建本地项目" onBack={leave} />
      <KeyboardAvoidingView style={{ flex: 1 }} behavior="padding">
        <ScrollView contentContainerStyle={{ paddingTop: 90, paddingBottom: insets.bottom + 40, paddingHorizontal: 14 }}>
          <Card style={{ padding: 16 }}>
            <Text style={[styles.label, { color: t.tx3 }]}>项目名称</Text>
            <TextInput
              placeholder="例如 demo-app"
              placeholderTextColor={t.tx3}
              value={name}
              onChangeText={setName}
              style={[styles.input, { backgroundColor: t.bg3, color: t.tx }]}
            />

            <Text style={[styles.label, { color: t.tx3, marginTop: 16 }]}>本地路径</Text>
            <TextInput
              placeholder={DEFAULT_PATH}
              placeholderTextColor={t.tx3}
              value={path}
              onChangeText={setPath}
              autoCapitalize="none"
              autoCorrect={false}
              style={[styles.input, { backgroundColor: t.bg3, color: t.tx, fontFamily: 'monospace' }]}
            />

            <Text style={[styles.label, { color: t.tx3, marginTop: 16 }]}>{params.mode === 'clone' ? 'Git 仓库地址' : '远程仓库地址（可选，克隆用）'}</Text>
            <TextInput
              placeholder="https://github.com/owner/repo.git"
              placeholderTextColor={t.tx3}
              value={remoteUrl}
              onChangeText={setRemoteUrl}
              autoCapitalize="none"
              autoCorrect={false}
              style={[styles.input, { backgroundColor: t.bg3, color: t.tx, fontFamily: 'monospace' }]}
            />

            {params.mode !== 'clone' && !remoteUrl.trim() && (
              <Pressable onPress={() => setGitInit(!gitInit)} style={{ flexDirection: 'row', alignItems: 'center', gap: 9, marginTop: 18 }}>
                <Pressable onPress={() => setGitInit(!gitInit)} style={{ width: 20, height: 20, borderRadius: 6, borderWidth: 1.5, borderColor: gitInit ? t.ac : t.tx3, alignItems: 'center', justifyContent: 'center', backgroundColor: gitInit ? t.ac : 'transparent' }}>
                  {gitInit ? <Icons.check size={13} color={t.acInk} sw={2.8} /> : null}
                </Pressable>
                <Text style={{ fontSize: 13.5, color: t.tx }}>初始化 Git 仓库</Text>
              </Pressable>
            )}
          </Card>

          <PrimaryButton block label={saving ? '创建中…' : '创建项目'} icon="plus" onPress={create} disabled={saving} style={{ marginTop: 16 }} />
          <Text style={{ fontSize: 11.5, color: t.tx3, textAlign: 'center', marginTop: 12, lineHeight: 17 }}>
            特权模式（Root）下可创建任意路径；沙箱模式仅创建本地记录。
          </Text>
        </ScrollView>
      </KeyboardAvoidingView>
    </View>
  );
}

const styles = StyleSheet.create({
  label: { fontSize: 12, fontWeight: '700', letterSpacing: 0.4, marginBottom: 8 },
  input: { height: 48, borderRadius: 12, paddingHorizontal: 14, fontSize: 14.5, fontWeight: '500' },
});
