const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

function withPrivilegedExecution(config) {
  config = withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;

    // 确保权限声明
    const permissions = [
      'android.permission.SYSTEM_ALERT_WINDOW',
      'android.permission.WRITE_EXTERNAL_STORAGE',
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.MANAGE_EXTERNAL_STORAGE',
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
    ];

    for (const perm of permissions) {
      asChild(manifest, 'uses-permission', { 'android:name': perm });
    }

    // 注册 AccessibilityService
    asChild(manifest.application, 'service', {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeAccessibilityService',
      'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
      'android:exported': 'true',
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
    asChild(manifest.application, 'service', {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeVoiceInteractionService',
      'android:permission': 'android.permission.BIND_VOICE_INTERACTION',
      'android:exported': 'true',
    }, {
      'intent-filter': {
        action: { 'android:name': 'android.service.voice.VoiceInteractionService' },
      },
      'meta-data': {
        'android:name': 'android.voice_interaction',
        'android:resource': '@xml/voice_interaction_service_config',
      },
    });

    return config;
  });

  config = withDangerousMod(config, ['android', (config) => {
    const projectRoot = config.modRequest.projectRoot;
    const androidDir = path.join(projectRoot, 'android');
    const privilegedDir = path.join(androidDir, 'app', 'src', 'main', 'java', 'com', 'monkeycode', 'privileged');
    const xmlDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'xml');

    // 创建目录
    fs.mkdirSync(privilegedDir, { recursive: true });
    fs.mkdirSync(xmlDir, { recursive: true });

    // 复制 AccessibilityService 配置文件
    const accessibilityConfig = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100"
    android:description="@string/accessibility_service_description" />`;

    fs.writeFileSync(path.join(xmlDir, 'accessibility_service_config.xml'), accessibilityConfig);

    // 复制 VoiceInteractionService 配置文件
    const voiceInteractionConfig = `<?xml version="1.0" encoding="utf-8"?>
<voice-interaction-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="com.monkeycode.privileged.MonkeyCodeVoiceInteractionSessionService"
    android:recognitionService="com.monkeycode.privileged.MonkeyCodeRecognitionService"
    android:supportsAssist="true"
    android:supportsLaunchVoiceAssistFromKeyguard="true" />`;

    fs.writeFileSync(path.join(xmlDir, 'voice_interaction_service_config.xml'), voiceInteractionConfig);

    return config;
  }]);

  return config;
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