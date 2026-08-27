/**
 * 技能详情/编辑页 —— SKILL.md 原文编辑，frontmatter 显示，保存/删除。
 */
import { useLocalSearchParams, useNavigation, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icons } from '@/components/Icons';
import { Card, GlassNav, PrimaryButton } from '@/components/ui';
import { useTheme } from '@/theme';

const KEY_USER_SKILLS = 'mc.userSkills';

interface SkillItem {
  name: string;
  description: string;
  enabled: boolean;
  builtin: boolean;
  content?: string;
}

export default function SkillDetailScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const navigation = useNavigation();
  const { name } = useLocalSearchParams<{ name: string }>();
  const decodedName = decodeURIComponent(name || '');

  const [content, setContent] = useState('');
  const [initialContent, setInitialContent] = useState('');
  const [skill, setSkill] = useState<SkillItem | null>(null);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    loadUserSkills().then((skills) => {
      const found = skills.find((s) => s.name === decodedName);
      if (found) {
        setSkill(found);
        setContent(found.content || '');
        setInitialContent(found.content || '');
      }
    });
  }, [decodedName]);

  useEffect(() => {
    setDirty(content !== initialContent);
  }, [content, initialContent]);

  const save = useCallback(async () => {
    const skills = await loadUserSkills();
    const idx = skills.findIndex((s) => s.name === decodedName);
    if (idx === -1) return;
    skills[idx].content = content;
    const frontmatter = parseFrontmatter(content);
    skills[idx].description = frontmatter.description || skills[idx].description;
    await saveUserSkills(skills);
    setInitialContent(content);
    setDirty(false);
    Alert.alert('已保存', '技能内容已更新');
  }, [content, decodedName]);

  const deleteSkill = useCallback(() => {
    Alert.alert('删除技能', `确定删除 "${decodedName}"？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive',
        onPress: async () => {
          const skills = await loadUserSkills();
          const next = skills.filter((s) => s.name !== decodedName);
          await saveUserSkills(next);
          if (router.canGoBack()) router.back();
        },
      },
    ]);
  }, [decodedName, router]);

  const leave = useCallback(() => {
    if (!navigation.isFocused()) return;
    if (dirty) {
      Alert.alert('未保存', '有未保存的更改，确定离开？', [
        { text: '继续编辑', style: 'cancel' },
        { text: '离开', style: 'destructive', onPress: () => { if (router.canGoBack()) router.back(); } },
      ]);
    } else {
      if (router.canGoBack()) router.back();
    }
  }, [navigation, router, dirty]);

  const fm = parseFrontmatter(content);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <GlassNav title={decodedName} onBack={leave} right={
        <Pressable onPress={deleteSkill} hitSlop={8} style={{ padding: 8 }}>
          <Icons.trash size={20} color={t.red} sw={1.8} />
        </Pressable>
      } />
      <KeyboardAvoidingView style={{ flex: 1 }} behavior="padding">
        <ScrollView contentContainerStyle={{ paddingTop: 90, paddingBottom: insets.bottom + 40, paddingHorizontal: 14 }}>
          {fm.name && (
            <Card style={{ marginTop: 10, padding: 16, gap: 8 }}>
              <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5 }}>Frontmatter</Text>
              <View style={{ gap: 4 }}>
                <FmRow label="name" value={fm.name} t={t} />
                <FmRow label="description" value={fm.description ?? ''} t={t} />
                {fm.paths ? <FmRow label="paths" value={fm.paths} t={t} /> : null}
              </View>
            </Card>
          )}

          <Card style={{ marginTop: 12, padding: 16 }}>
            <Text style={{ fontSize: 12, fontWeight: '700', color: t.tx3, letterSpacing: 0.5, marginBottom: 10 }}>
              SKILL.md{dirty ? ' (已修改)' : ''}
            </Text>
            <TextInput
              value={content}
              onChangeText={setContent}
              multiline
              textAlignVertical="top"
              autoCapitalize="none"
              autoCorrect={false}
              style={{
                minHeight: 320,
                borderRadius: 10,
                paddingHorizontal: 12,
                paddingVertical: 10,
                backgroundColor: t.bg3,
                color: t.tx,
                fontSize: 13,
                fontFamily: 'monospace',
                lineHeight: 20,
              }}
              placeholder="---&#10;name: my-skill&#10;description: 技能描述&#10;---&#10;&#10;# 技能内容&#10;"
              placeholderTextColor={t.tx3}
            />
          </Card>

          <PrimaryButton
            label="保存"
            icon="check"
            onPress={() => void save()}
            disabled={!dirty}
            block
            style={{ marginTop: 14 }}
          />
        </ScrollView>
      </KeyboardAvoidingView>
    </View>
  );
}

function FmRow({ label, value, t }: { label: string; value: string; t: any }) {
  return (
    <View style={{ flexDirection: 'row', gap: 8 }}>
      <Text style={{ fontSize: 12, fontWeight: '600', color: t.tx2, fontFamily: 'monospace', minWidth: 80 }}>{label}:</Text>
      <Text style={{ fontSize: 12, color: t.tx, flex: 1 }}>{value}</Text>
    </View>
  );
}

function parseFrontmatter(content: string): { name?: string; description?: string; paths?: string } {
  if (!content.startsWith('---')) return {};
  const end = content.indexOf('---', 3);
  if (end === -1) return {};
  const fm = content.substring(3, end);
  const result: Record<string, string> = {};
  fm.split('\n').forEach((line) => {
    const m = line.match(/^(\w+):\s*(.+)/);
    if (m) result[m[1]] = m[2].trim();
  });
  return result;
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