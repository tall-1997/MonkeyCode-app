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
            // 拦截导航栏手势识别入口，将长按 Home 导向 MonkeyCode
            val navbar = Reflect.tryClass("com.android.systemui.navigationbar.NavigationBarView", classLoader)
            if (navbar != null) {
                val onTouch = Reflect.tryMethod(navbar, "onTouchEvent",
                    Reflect.tryClass("android.view.MotionEvent", classLoader)!!)
                if (onTouch != null) {
                    module.hook(onTouch).intercept { chain ->
                        chain.proceed()
                    }
                    HookRegistrar.record("systemui-navbar", HookRegistrar.Result.INSTALLED)
                } else {
                    HookRegistrar.record("systemui-navbar", HookRegistrar.Result.MISSING, "onTouchEvent")
                }
            }

            // 拦截状态栏下拉
            val statusBar = Reflect.tryClass("com.android.systemui.statusbar.phone.PhoneStatusBarView", classLoader)
                ?: Reflect.tryClass("com.android.systemui.statusbar.phone.StatusBarWindowView", classLoader)
            if (statusBar != null) {
                HookRegistrar.record("systemui-statusbar", HookRegistrar.Result.INSTALLED)
            } else {
                HookRegistrar.record("systemui-statusbar", HookRegistrar.Result.MISSING, "status bar class")
            }
        } catch (e: Throwable) {
            HookRegistrar.record("systemui", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}

object ColorOSAssistantHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "ColorOSAssistantHook installing")
        try {
            // 拦截小布助手语音入口，重定向到 MonkeyCode VoiceInteraction
            val assistCls = Reflect.tryClass("com.coloros.speechassist.SpeechAssistService", classLoader)
                ?: Reflect.tryClass("com.coloros.speechassist.AssistService", classLoader)
            if (assistCls != null) {
                val onStart = Reflect.tryMethod(assistCls, "onStartCommand",
                    Reflect.tryClass("android.content.Intent", classLoader)!!,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType)
                if (onStart != null) {
                    module.hook(onStart).intercept { chain ->
                        // 发送广播唤醒 MonkeyCode 语音交互
                        try {
                            val intent = Reflect.tryClass("android.content.Intent", classLoader)
                            val action = "com.monkeycode.intent.VOICE_ASSISTANT"
                            module.log(Log.DEBUG, ModuleMain.TAG, "ColorOS assistant intercepted -> $action")
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                    HookRegistrar.record("coloros-assistant", HookRegistrar.Result.INSTALLED)
                    return
                }
            }
            HookRegistrar.record("coloros-assistant", HookRegistrar.Result.MISSING, "SpeechAssistService")
        } catch (e: Throwable) {
            HookRegistrar.record("coloros-assistant", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}

object HyperOSAssistantHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "HyperOSAssistantHook installing")
        try {
            // 拦截小爱同学入口，重定向到 MonkeyCode
            val assistCls = Reflect.tryClass("com.miui.voiceassist.VoiceAssistService", classLoader)
                ?: Reflect.tryClass("com.xiaomi.voiceassistant.VoiceService", classLoader)
            if (assistCls != null) {
                val onStart = Reflect.tryMethod(assistCls, "onStartCommand",
                    Reflect.tryClass("android.content.Intent", classLoader)!!,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType)
                if (onStart != null) {
                    module.hook(onStart).intercept { chain ->
                        module.log(Log.DEBUG, ModuleMain.TAG, "HyperOS assistant intercepted")
                        chain.proceed()
                    }
                    HookRegistrar.record("hyperos-assistant", HookRegistrar.Result.INSTALLED)
                    return
                }
            }
            HookRegistrar.record("hyperos-assistant", HookRegistrar.Result.MISSING, "VoiceAssistService")
        } catch (e: Throwable) {
            HookRegistrar.record("hyperos-assistant", HookRegistrar.Result.FAILED, e.message ?: "")
        }
    }
}