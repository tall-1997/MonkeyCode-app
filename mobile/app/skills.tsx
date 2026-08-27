/**
 * 技能管理页面 —— 内置与用户技能列表，启用/禁用开关，新增/编辑/删除。
 */
import { useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { Card, GlassNav, PrimaryButton } from '@/components/ui';
import { useTheme } from '@/theme';

interface SkillItem {
  name: string;
  description: string;
  enabled: boolean;
  builtin: boolean;
  content?: string;
}

const BUILTIN_SKILLS: SkillItem[] = [
  { name: 'feature-design', description: '需求文档与技术设计', enabled: true, builtin: true },
  { name: 'project-wiki', description: '项目文档生成与同步', enabled: true, builtin: true },
  { name: 'feature-implementer', description: '按任务列表实施开发', enabled: true, builtin: true },
  { name: 'implementation-planner', description: '设计方案转任务列表', enabled: true, builtin: true },
  { name: 'deploy-website', description: '部署并预览 Web 项目', enabled: true, builtin: true },
  { name: 'golang-code-review', description: 'Go 代码审查', enabled: false, builtin: true },
  { name: 'golang-testing', description: 'Go 测试模式与最佳实践', enabled: false, builtin: true },
  { name: 'ui-ux-pro-max', description: 'UI/UX 设计智能', enabled: false, builtin: true },
];

const KEY_USER_SKILLS = 'mc.userSkills';

export default function SkillsScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();

  const [builtinSkills, setBuiltinSkills] = useState<SkillItem[]>(BUILTIN_SKILLS);
  const [userSkills, setUserSkills] = useState<SkillItem[]>([]);
  const [newName, setNewName] = useState('');
  const [showNew, setShowNew] = useState(false);

  useEffect(() => {
    loadUserSkills().then(setUserSkills);
    loadBuiltinConfig().then((cfg) => {
      if (cfg) {
        setBuiltinSkills((prev) => prev.map((s) => ({
          ...s,
          enabled: cfg[s.name] ?? s.enabled,
        })));
      }
    });
  }, []);

  const toggleBuiltin = useCallback((name: string, enabled: boolean) => {
    setBuiltinSkills((prev) => {
      const next = prev.map((s) => (s.name === name ? { ...s, enabled } : s));
      saveBuiltinConfig(next);
      return next;
    });
  }, []);

  const toggleUser = useCallback((name: string, enabled: boolean) => {
    setUserSkills((prev) => {
      const next = prev.map((s) => (s.name === name ? { ...s, enabled } : s));
      saveUserSkills(next);
      return next;
    });
  }, []);

  const createSkill = useCallback(() => {
    const name = newName.trim();
    if (!name) { Alert.alert('提示', '请输入技能名称'); return; }
    if (userSkills.some((s) => s.name === name)) { Alert.alert('提示', '技能名称重复'); return; }
    setUserSkills((prev) => {
      const next = [...prev, { name, description: '', enabled: true, builtin: false, content: `# ${name}\n\n## 描述\n\n` }];
      saveUserSkills(next);
      return next;
    });
    setNewName('');
    setShowNew(false);
    router.push(`/skill/${encodeURIComponent(name)}`);
  }, [newName, userSkills, router]);

  const deleteSkill = useCallback((name: string) => {
    Alert.alert('删除技能', `确定删除 "${name}"？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive',
        onPress: () => {
          setUserSkills((prev) => {
            const next = prev.filter((s) => s.name !== name);
            saveUserSkills(next);
            return next;
          });
        },
      },
    ]);
  }, []);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (router.canGoBack()) router.back();
    else router.replace('/');
  }, [navigation, router]);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title="技能管理" onBack={leave} />
      <ScrollView contentContainerStyle={{ paddingTop: 90, paddingBottom: insets.bottom + 40, paddingHorizontal: 14 }}>
        <Card style={{ marginTop: 10, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>内置技能</Text>
          {builtinSkills.map((s, i) => (
            <View key={s.name} style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13, paddingHorizontal: 16, borderTopWidth: i > 0 ? StyleSheet.hairlineWidth : 0, borderColor: t.line }}>
              <View style={{ flex: 1, minWidth: 0 }}>
                <Text style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>{s.name}</Text>
                <Text style={{ fontSize: 11.5, color: t.tx3, marginTop: 2 }}>{s.description}</Text>
              </View>
              <Switch value={s.enabled} onValueChange={(v) => toggleBuiltin(s.name, v)}
                trackColor={{ false: t.bg4, true: t.acGhost }} thumbColor={s.enabled ? t.ac : t.tx3} ios_backgroundColor={t.bg4} />
            </View>
          ))}
        </Card>

        <Card style={{ marginTop: 12, overflow: 'hidden' }}>
          <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, paddingHorizontal: 16, paddingTop: 14, paddingBottom: 6 }}>用户技能</Text>
          {userSkills.length === 0 && (
            <Text style={{ fontSize: 13, color: t.tx3, paddingHorizontal: 16, paddingVertical: 12 }}>暂无自定义技能</Text>
          )}
          {userSkills.map((s, i) => (
            <Pressable key={s.name} onPress={() => router.push(`/skill/${encodeURIComponent(s.name)}`)}
              style={{ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13, paddingHorizontal: 16, borderTopWidth: StyleSheet.hairlineWidth, borderColor: t.line }}>
              <View style={{ flex: 1, minWidth: 0 }}>
                <Text style={{ fontSize: 14.5, fontWeight: '600', color: t.tx }}>{s.name}</Text>
                {s.description ? <Text style={{ fontSize: 11.5, color: t.tx3, marginTop: 2 }}>{s.description}</Text> : null}
              </View>
              <Switch value={s.enabled} onValueChange={(v) => toggleUser(s.name, v)}
                trackColor={{ false: t.bg4, true: t.acGhost }} thumbColor={s.enabled ? t.ac : t.tx3} ios_backgroundColor={t.bg4} />
              <Pressable onPress={() => deleteSkill(s.name)} hitSlop={8}>
                <Icons.trash size={18} color={t.redGhost} />
              </Pressable>
            </Pressable>
          ))}
        </Card>

        {showNew ? (
          <Card style={{ marginTop: 12, padding: 16, gap: 10 }}>
            <TextInput
              value={newName}
              onChangeText={setNewName}
              placeholder="技能名称"
              placeholderTextColor={t.tx3}
              autoCapitalize="none"
              autoCorrect={false}
              onSubmitEditing={createSkill}
              style={{ height: 42, borderRadius: 10, paddingHorizontal: 12, backgroundColor: t.bg3, color: t.tx, fontSize: 14 }}
            />
            <PrimaryButton label="创建技能" icon="plus" onPress={createSkill} block />
          </Card>
        ) : (
          <Pressable onPress={() => setShowNew(true)} style={{ marginTop: 12, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingVertical: 14, borderRadius: 14, borderWidth: 1, borderColor: t.line2, borderStyle: 'dashed' }}>
            <Icons.plus size={18} color={t.tx3} sw={1.8} />
            <Text style={{ fontSize: 14, color: t.tx3, fontWeight: '500' }}>新增用户技能</Text>
          </Pressable>
        )}
      </ScrollView>
    </View>
  );
}

async function loadUserSkills(): Promise<SkillItem[]> {
  try {
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    const raw = await AsyncStorage.getItem(KEY_USER_SKILLS);
    return raw ? JSON.parse(raw) : [];
  } catch { return []; }
}

async function saveUserSkills(skills: SkillItem[]): Promise<void> {
  try {
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    await AsyncStorage.setItem(KEY_USER_SKILLS, JSON.stringify(skills));
  } catch { /* ignore */ }
}

async function loadBuiltinConfig(): Promise<Record<string, boolean> | null> {
  try {
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    const raw = await AsyncStorage.getItem('mc.skillsDefaults');
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

async function saveBuiltinConfig(skills: SkillItem[]): Promise<void> {
  try {
    const { AsyncStorage } = require('@react-native-async-storage/async-storage');
    const cfg: Record<string, boolean> = {};
    skills.forEach((s) => { cfg[s.name] = s.enabled; });
    await AsyncStorage.setItem('mc.skillsDefaults', JSON.stringify(cfg));
  } catch { /* ignore */ }
}