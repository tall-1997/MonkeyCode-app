const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

function withPrivilegedExecution(config) {
  config = withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;

    // 确保权限声明
    const permissions = [
      'android.permission.SYSTEM_ALERT_WINDOW',
      'android.permission.READ_SMS',
      'android.permission.READ_CALENDAR',
      'android.permission.READ_CONTACTS',
      'android.permission.ACCESS_FINE_LOCATION',
      'android.permission.ACCESS_COARSE_LOCATION',
      'android.permission.PACKAGE_USAGE_STATS',
      'android.permission.QUERY_ALL_PACKAGES',
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
      'android.permission.POST_NOTIFICATIONS',
      'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
      'android.permission.BIND_ACCESSIBILITY_SERVICE',
      'android.permission.BIND_VOICE_INTERACTION',
    ];

    for (const perm of permissions) {
      asChild(manifest, 'uses-permission', { 'android:name': perm });
    }

    // application 在 Expo manifest 结构中是数组，取第一个元素
    const mainApplication = Array.isArray(manifest.application)
      ? manifest.application[0]
      : manifest.application;

    // 注册 AccessibilityService
    asChild(mainApplication, 'service', {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeAccessibilityService',
      'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
      'android:exported': 'true',
      'android:label': '@string/app_name',
    }, {
      'intent-filter': {
        action: { 'android:name': 'android.accessibilityservice.AccessibilityService' },
      },
      'meta-data': {
        'android:name': 'android.accessibilityservice',
        'android:resource': '@xml/accessibility_service_config',
      },
    });

    // 注册 VoiceInteractionService
    asChild(mainApplication, 'service', {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeVoiceInteractionService',
      'android:permission': 'android.permission.BIND_VOICE_INTERACTION',
      'android:exported': 'true',
      'android:label': '@string/app_name',
    }, {
      'intent-filter': {
        action: { 'android:name': 'android.service.voice.VoiceInteractionService' },
      },
      'meta-data': {
        'android:name': 'android.voice_interaction',
        'android:resource': '@xml/voice_interaction_service_config',
      },
    });

    // 注册 VoiceInteractionSessionService
    asChild(mainApplication, 'service', {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeVoiceInteractionSessionService',
      'android:permission': 'android.permission.BIND_VOICE_INTERACTION',
      'android:exported': 'true',
    }, {
      'intent-filter': {
        action: { 'android:name': 'android.service.voice.VoiceInteractionSessionService' },
      },
    });

    // 注册 RecognitionService
    asChild(mainApplication, 'service', {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeRecognitionService',
      'android:permission': 'android.permission.BIND_RECOGNITION_SERVICE',
      'android:exported': 'true',
    }, {
      'intent-filter': {
        action: { 'android:name': 'android.speech.RecognitionService' },
      },
      'meta-data': {
        'android:name': 'android.speech.recognition',
        'android:resource': '@xml/recognition_service_config',
      },
    });

    return config;
  });

  config = withDangerousMod(config, ['android', (config) => {
    const projectRoot = config.modRequest.projectRoot;
    const androidDir = path.join(projectRoot, 'android');

    const privilegedDir = path.join(androidDir, 'app', 'src', 'main', 'java', 'com', 'monkeycode', 'privileged');
    const xmlDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'xml');

    fs.mkdirSync(privilegedDir, { recursive: true });
    fs.mkdirSync(xmlDir, { recursive: true });

    // 拷贝 Kotlin 原生模块源码
    const kotlinSrcDir = path.join(projectRoot, 'native-android', 'kotlin', 'com', 'monkeycode', 'privileged');
    if (fs.existsSync(kotlinSrcDir)) {
      for (const file of fs.readdirSync(kotlinSrcDir)) {
        if (file.endsWith('.kt')) {
          fs.copyFileSync(
            path.join(kotlinSrcDir, file),
            path.join(privilegedDir, file)
          );
        }
      }
    }

    // 拷贝 XML 资源
    const xmlSrcDir = path.join(projectRoot, 'native-android', 'res', 'xml');
    if (fs.existsSync(xmlSrcDir)) {
      for (const file of fs.readdirSync(xmlSrcDir)) {
        if (file.endsWith('.xml')) {
          fs.copyFileSync(
            path.join(xmlSrcDir, file),
            path.join(xmlDir, file)
          );
        }
      }
    }

    // 确保 accessibility 描述字符串存在
    ensureStringResource(androidDir);

    // 注册 MonkeyCodePackage 到 MainApplication
    registerPackage(projectRoot);

    return config;
  }]);

  return config;
}

function ensureStringResource(androidDir) {
  const valuesDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'values');
  const stringsFile = path.join(valuesDir, 'strings.xml');
  if (!fs.existsSync(stringsFile)) return;

  let content = fs.readFileSync(stringsFile, 'utf8');
  if (!content.includes('accessibility_service_description')) {
    content = content.replace(
      '</resources>',
      '    <string name="accessibility_service_description">MonkeyCode 无障碍服务 - 用于 AI Agent 操作手机屏幕</string>\n</resources>'
    );
    fs.writeFileSync(stringsFile, content);
  }
}

function registerPackage(projectRoot) {
  const androidDir = path.join(projectRoot, 'android');
  const mainAppPath = findMainApplication(androidDir);
  if (!mainAppPath) return;

  let content = fs.readFileSync(mainAppPath, 'utf8');
  const addStatement = 'add(com.monkeycode.privileged.MonkeyCodePackage())';
  if (content.includes(addStatement)) return;

  // 在 packageList 的 apply 块内追加注册（使用全限定名避免 import 冲突）
  const applyAnchor = 'PackageList(this).packages.apply {';
  if (content.includes(applyAnchor)) {
    const idx = content.indexOf(applyAnchor) + applyAnchor.length;
    content =
      content.slice(0, idx) +
      '\n          add(com.monkeycode.privileged.MonkeyCodePackage())' +
      content.slice(idx);
    fs.writeFileSync(mainAppPath, content);
  }
}

function findMainApplication(androidDir) {
  const javaDir = path.join(androidDir, 'app', 'src', 'main', 'java');
  if (!fs.existsSync(javaDir)) return null;
  const matches = [];
  (function walk(dir) {
    if (!fs.existsSync(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.name === 'MainApplication.kt' || entry.name === 'MainApplication.java') {
        matches.push(full);
      }
    }
  })(javaDir);
  return matches[0] || null;
}

function asChild(parent, tag, attrs, children) {
  if (!parent[tag]) {
    parent[tag] = [];
  }
  const child = { $: attrs };
  if (children) {
    for (const [key, value] of Object.entries(children)) {
      if (Array.isArray(value)) {
        child[key] = value.map((v) => ({ $: v }));
      } else {
        child[key] = [value];
      }
    }
  }
  parent[tag].push(child);
  return child;
}

module.exports = withPrivilegedExecution;