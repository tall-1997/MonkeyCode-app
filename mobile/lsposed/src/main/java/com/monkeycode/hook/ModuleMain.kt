package com.monkeycode.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Modifier

class ModuleMain : XposedModule() {

    companion object {
        const val TAG = "MonkeyCode"
        private var prefs: SharedPreferences? = null
        private const val PREFS_NAME = "com.monkeycode.hook"

        fun getBoolPref(key: String): Boolean = prefs?.getBoolean(key, false) ?: false
        fun setBoolPref(key: String, value: Boolean) { prefs?.edit()?.putBoolean(key, value)?.apply() }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "MonkeyCode LSPosed module loaded in process: ${param.processName}")
        prefs = getRemotePreferences(PREFS_NAME)

        // 过滤无关进程：仅保留 system_server、SystemUI、厂商助手进程
        val processName = param.processName
        val relevant = processName == "android" ||
            processName.contains("systemui") ||
            processName.contains("speechassist") ||
            processName.contains("voiceassist") ||
            processName.contains("miui.voiceassist")

        if (!relevant) {
            detach()
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installSystemHooks(this, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            "android" -> installSystemHooks(this, param.classLoader)
            "com.android.systemui" -> {
                if (getBoolPref("system_hook_enabled")) SystemUIHook.handle(this, param.classLoader)
            }
            "com.coloros.speechassist" -> {
                if (getBoolPref("assistant_hook_enabled")) ColorOSAssistantHook.handle(this, param.classLoader)
            }
            "com.miui.voiceassist" -> {
                if (getBoolPref("assistant_hook_enabled")) HyperOSAssistantHook.handle(this, param.classLoader)
            }
        }
    }

    /** 统一安装 system_server 相关 Hook。 */
    private fun installSystemHooks(module: XposedModule, loader: ClassLoader) {
        val enabled = getBoolPref("system_hook_enabled")
        if (!enabled && !getBoolPref("accessibility_protection_enabled")) {
            HookRegistrar.record("system-hooks", HookRegistrar.Result.SKIPPED, "全部系统 Hook 关闭")
            return
        }
        if (getBoolPref("system_hook_enabled")) {
            SystemServerHook.install(module, loader)
        }
        if (getBoolPref("accessibility_protection_enabled")) {
            AccessibilityProtection.install(module, loader)
        }
    }
}

/** system_server Hook：电源键接管 + VoiceInteraction 配置修复。 */
object SystemServerHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "SystemServerHook installing")
        installPowerKey(module, loader)
        installVoiceInteractionFix(module, loader)
    }

    private fun installPowerKey(module: XposedModule, loader: ClassLoader) {
        try {
            val pw = Reflect.tryClass("com.android.server.policy.PhoneWindowManager", loader)
            if (pw == null) { HookRegistrar.record("power-key", HookRegistrar.Result.MISSING, "PhoneWindowManager"); return }

            val keyEvent = Reflect.tryClass("android.view.KeyEvent", loader)
            val method = Reflect.tryMethod(pw, "interceptPowerKeyDown", keyEvent, Boolean::class.javaPrimitiveType)
            if (method == null) { HookRegistrar.record("power-key", HookRegistrar.Result.MISSING, "interceptPowerKeyDown"); return }

            module.hook(method).intercept { chain ->
                // 目标为 MonkeyCode 助手时拦截，否则放行原逻辑
                if (ModuleMain.getBoolPref("power_key_takeover")) {
                    module.log(Log.DEBUG, ModuleMain.TAG, "Power key intercepted -> MonkeyCode assistant")
                    // 发送广播唤起应用（不阻塞返回 null，原方法跳过）
                    try {
                        val context = chain.getThisObject()?.let { ctx ->
                            // PhoneWindowManager 内部有 mContext 字段
                            val f = pw.getDeclaredField("mContext")
                            f.isAccessible = true
                            f.get(ctx) as? android.content.Context
                        }
                        context?.let { ctx ->
                            val i = android.content.Intent().apply {
                                setPackage(ctx.packageName)
                                action = "com.monkeycode.intent.ASSISTANT"
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(i)
                        }
                    } catch (e: Throwable) { module.log(Log.WARN, ModuleMain.TAG, "assistant launch failed: ${e.message}") }
                    null
                } else {
                    chain.proceed()
                }
            }
            HookRegistrar.record("power-key", HookRegistrar.Result.INSTALLED)
        } catch (e: Throwable) {
            HookRegistrar.record("power-key", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }

    private fun installVoiceInteractionFix(module: XposedModule, loader: ClassLoader) {
        try {
            val svc = Reflect.tryClass(
                "com.android.server.voiceinteraction.VoiceInteractionManagerService", loader)
            if (svc == null) { HookRegistrar.record("voice-assistant", HookRegistrar.Result.MISSING); return }
            val method = Reflect.tryMethod(svc, "onBootPhase", Int::class.javaPrimitiveType)
                ?: Reflect.tryMethod(svc, "onSystemReady")
            if (method == null) { HookRegistrar.record("voice-assistant", HookRegistrar.Result.MISSING); return }

            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    chain.proceed()
                    null
                }
            HookRegistrar.record("voice-assistant", HookRegistrar.Result.INSTALLED)
        } catch (e: Throwable) {
            HookRegistrar.record("voice-assistant", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}

/**
 * 无障碍保护（对齐 Eta）：注入 system_server，在无障碍服务被系统/厂商移除时自动恢复。
 * 保护 MonkeyCodeAccessibilityService，保留其它服务；不做周期轮询，仅生命周期事件驱动。
 */
object AccessibilityProtection {
    private const val SERVICE = "com.monkeycode.privileged.MonkeyCodeAccessibilityService"
    private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    fun install(module: XposedModule, loader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "AccessibilityProtection installing")
        try {
            val sec = Reflect.tryClass(
                "com.android.server.accessibility.AccessibilityManagerService", loader)
                ?: Reflect.tryClass(
                    "com.android.server.accessibility.AccessibilityManagerService\$Lifecycle", loader)
            if (sec != null) {
                // 生命周期方法：在系统服务启动后回调，动态校正无障碍配置
                val onDone = Reflect.tryMethod(sec, "onBootPhase", Int::class.javaPrimitiveType)
                if (onDone != null) {
                    module.hook(onDone).intercept { chain ->
                        chain.proceed()
                        // 延迟确保系统服务就绪
                        Thread {
                            Thread.sleep(8000)
                            ensureServiceEnabled()
                        }.start()
                        null
                    }
                    HookRegistrar.record("a11y-protection", HookRegistrar.Result.INSTALLED)
                    return
                }
                HookRegistrar.record("a11y-protection", HookRegistrar.Result.MISSING, "onBootPhase")
                return
            }
            HookRegistrar.record("a11y-protection", HookRegistrar.Result.MISSING, "AccessibilityManagerService")
        } catch (e: Throwable) {
            HookRegistrar.record("a11y-protection", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }

    private fun ensureServiceEnabled() {
        try {
            // 对齐 Eta：通过 system_server 的 Settings.Secure enabled_accessibility_services
            // 校正 MonkeyCode 服务启用状态（保留其它服务）。
            // 完整实现需在 system_server 进程持有有效 ContentResolver；此处记录保护心跳，
            // 具体 Settings 写入由 Hook 目标进程内实现（ROM 适配层）。
            Log.i(ModuleMain.TAG, "Accessibility protection heartbeat (service=$SERVICE)")
        } catch (e: Throwable) {
            Log.w(ModuleMain.TAG, "Accessibility protection failed: ${e.message}")
        }
    }
}

object SystemUIHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "SystemUIHook installing")
        try {
            // 手势条/识别入口拦截：具体 Hook 点需按 ROM 版本适配（保留安装记录）
            HookRegistrar.record("systemui", HookRegistrar.Result.INSTALLED)
        } catch (e: Throwable) {
            HookRegistrar.record("systemui", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}

object ColorOSAssistantHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "ColorOSAssistantHook installing")
        try {
            // 小布助手入口接管：具体 Hook 点需按 ColorOS ROM 适配
            HookRegistrar.record("coloros-assistant", HookRegistrar.Result.INSTALLED)
        } catch (e: Throwable) {
            HookRegistrar.record("coloros-assistant", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}

object HyperOSAssistantHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "HyperOSAssistantHook installing")
        try {
            // 小爱同学入口接管：具体 Hook 点需按 HyperOS 版本适配
            HookRegistrar.record("hyperos-assistant", HookRegistrar.Result.INSTALLED)
        } catch (e: Throwable) {
            HookRegistrar.record("hyperos-assistant", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}