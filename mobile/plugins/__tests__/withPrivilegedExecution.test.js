const fs = require('fs');
const path = require('path');

const pluginPath = path.join(__dirname, '..', 'withPrivilegedExecution.js');
const pluginSource = fs.readFileSync(pluginPath, 'utf8');

describe('withPrivilegedExecution Xposed integration', () => {
  test('injects the API 102 dependencies and metadata packaging', () => {
    expect(pluginSource).toContain('io.github.libxposed:api:102.0.0');
    expect(pluginSource).toContain('io.github.libxposed:service:102.0.0');
    expect(pluginSource).toContain('META-INF/xposed/java_init.list');
    expect(pluginSource).toContain('META-INF/xposed/module.prop');
    expect(pluginSource).toContain('META-INF/xposed/scope.list');
  });

  test('registers the Xposed provider and application bridge', () => {
    expect(pluginSource).toContain('io.github.libxposed.service.XposedProvider');
    expect(pluginSource).toContain('com.monkeycode.hook.XposedServiceBridge.initialize(this)');
  });

  test('requires Android API 26 for libxposed service', () => {
    const appConfig = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'app.json'), 'utf8'));
    const buildProperties = appConfig.expo.plugins.find((plugin) => Array.isArray(plugin) && plugin[0] === 'expo-build-properties');
    expect(buildProperties[1].android.minSdkVersion).toBe(26);
  });
});
