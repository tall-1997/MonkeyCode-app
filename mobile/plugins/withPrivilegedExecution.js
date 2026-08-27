const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

function withPrivilegedExecution(config) {
  config = withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;

    // 确保权限声明（与 app.json 中已声明的权限去重）
    // app.json 已含：SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
    // FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, QUERY_ALL_PACKAGES
    const permissions = [
      'android.permission.READ_SMS',
      'android.permission.READ_CALENDAR',
      'android.permission.READ_CONTACTS',
      'android.permission.ACCESS_FINE_LOCATION',
      'android.permission.ACCESS_COARSE_LOCATION',
      'android.permission.PACKAGE_USAGE_STATS',
      'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
      'android.permission.BIND_ACCESSIBILITY_SERVICE',
      'android.permission.BIND_VOICE_INTERACTION',
    ];

    for (const perm of permissions) {
      manifest['uses-permission'] = manifest['uses-permission'] || [];
      manifest['uses-permission'].push({ $: { 'android:name': perm } });
    }

    // application 在 Expo manifest 结构中是数组，取第一个元素
    const mainApplication = Array.isArray(manifest.application)
      ? manifest.application[0]
      : manifest.application;

    // 注册 AccessibilityService
    addService(mainApplication, {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeAccessibilityService',
      'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
      'android:exported': 'true',
      'android:label': '@string/app_name',
      'intent-filter': addIntentFilter('android.accessibilityservice.AccessibilityService'),
      'meta-data': addMetaData('android.accessibilityservice', '@xml/accessibility_service_config'),
    });

    // 注册 VoiceInteractionService
    addService(mainApplication, {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeVoiceInteractionService',
      'android:permission': 'android.permission.BIND_VOICE_INTERACTION',
      'android:exported': 'true',
      'android:label': '@string/app_name',
      'intent-filter': addIntentFilter('android.service.voice.VoiceInteractionService'),
      'meta-data': addMetaData('android.voice_interaction', '@xml/voice_interaction_service_config'),
    });

    // 注册 VoiceInteractionSessionService
    addService(mainApplication, {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeVoiceInteractionSessionService',
      'android:permission': 'android.permission.BIND_VOICE_INTERACTION',
      'android:exported': 'true',
      'intent-filter': addIntentFilter('android.service.voice.VoiceInteractionSessionService'),
    });

    // 注册 RecognitionService
    addService(mainApplication, {
      'android:name': 'com.monkeycode.privileged.MonkeyCodeRecognitionService',
      'android:permission': 'android.permission.BIND_RECOGNITION_SERVICE',
      'android:exported': 'true',
      'intent-filter': addIntentFilter('android.speech.RecognitionService'),
      'meta-data': addMetaData('android.speech.recognition', '@xml/recognition_service_config'),
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

    // 拷贝 Linux 沙箱资源（PRoot + Alpine minirootfs）到 APK assets
    // 由 scripts/prepare_android_sandbox.sh 构建期生成；缺失时运行时走在线兜底
    copySandboxAssets(projectRoot, androidDir);

    // 确保 accessibility 描述字符串存在
    ensureStringResource(androidDir);

    // 优化 Gradle 构建配置（内存/并发），避免 CI 上 NDK 并行编译卡死
    tuneGradleProperties(androidDir);

    // 注册 MonkeyCodePackage 到 MainApplication
    registerPackage(projectRoot);

    return config;
  }]);

  return config;
}

function addService(application, service) {
  const services = application['service'] || (application['service'] = []);
  const svc = { $: {} };
  for (const [k, v] of Object.entries(service)) {
    if (k === 'intent-filter' || k === 'meta-data') {
      const arr = Array.isArray(v) ? v : [v];
      svc[k] = arr;
    } else {
      svc.$[k] = v;
    }
  }
  services.push(svc);
}

function addIntentFilter(actionName) {
  return {
    $: {},
    action: [{ $: { 'android:name': actionName } }],
  };
}

function addMetaData(name, resource) {
  return { $: { 'android:name': name, 'android:resource': resource } };
}

function tuneGradleProperties(androidDir) {
  const propsFile = path.join(androidDir, 'gradle.properties');
  if (!fs.existsSync(propsFile)) return;

  let content = fs.readFileSync(propsFile, 'utf8');

  // 提升 Gradle JVM 内存（runner 7GB，分配 4GB）
  if (content.includes('-Xmx2048m')) {
    content = content.replace(
      'org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m',
      'org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m'
    );
  } else if (!content.includes('-Xmx')) {
    content = 'org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m\n' + content;
  }

  // 限制 Gradle 并行 worker 数，避免 2 核 runner 上 NDK 编译资源争抢
  if (!content.includes('org.gradle.workers.max')) {
    content += '\norg.gradle.workers.max=2\n';
  }

  fs.writeFileSync(propsFile, content);
}

function copySandboxAssets(projectRoot, androidDir) {
  const srcDir = path.join(projectRoot, 'native-android', 'assets');
  const assetsMain = path.join(androidDir, 'app', 'src', 'main', 'assets');
  if (!fs.existsSync(srcDir)) return;
  fs.mkdirSync(assetsMain, { recursive: true });
  // 递归复制 native-android/assets/* → android app assets/
  (function copy(src, dest) {
    if (!fs.existsSync(src)) return;
    for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
      const s = path.join(src, entry.name);
      const d = path.join(dest, entry.name);
      if (entry.isDirectory()) {
        fs.mkdirSync(d, { recursive: true });
        copy(s, d);
      } else if (entry.isFile()) {
        fs.copyFileSync(s, d);
      }
    }
  })(srcDir, assetsMain);
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

module.exports = withPrivilegedExecution;