package com.monkeycode.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModuleMain : XposedModule() {

    companion object {
        const val TAG = "MonkeyCode"
        private var prefs: SharedPreferences? = null

        fun getBoolPref(key: String): Boolean = prefs?.getBoolean(key, false) ?: false
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "MonkeyCode LSPosed module loaded in process: ${param.processName}")
        prefs = getRemotePreferences("com.monkeycode.hook")

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
        if (getBoolPref("system_hook_enabled")) {
            SystemServerHook.handle(this, param.classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            "android" -> {
                if (getBoolPref("system_hook_enabled")) {
                    SystemServerHook.handle(this, param.classLoader)
                }
            }

            "com.android.systemui" -> {
                if (getBoolPref("system_hook_enabled")) {
                    SystemUIHook.handle(this, param.classLoader)
                }
            }

            "com.coloros.speechassist" -> {
                if (getBoolPref("assistant_hook_enabled")) {
                    ColorOSAssistantHook.handle(this, param.classLoader)
                }
            }

            "com.miui.voiceassist" -> {
                if (getBoolPref("assistant_hook_enabled")) {
                    HyperOSAssistantHook.handle(this, param.classLoader)
                }
            }
        }
    }
}

object SystemServerHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "SystemServerHook installing")
        try {
            // Hook PhoneWindowManager 的电源键处理
            val pwClass = Class.forName(
                "com.android.server.policy.PhoneWindowManager",
                false,
                classLoader
            )
            val keyEventClass = Class.forName("android.view.KeyEvent", false, classLoader)
            val method = pwClass.getDeclaredMethod(
                "interceptPowerKeyDown",
                keyEventClass,
                Boolean::class.javaPrimitiveType
            )

            module.hook(method).intercept { chain ->
                module.log(Log.INFO, ModuleMain.TAG, "Power key intercepted")
                chain.proceed()
            }
            module.log(Log.INFO, ModuleMain.TAG, "Power key hook installed")
        } catch (e: Throwable) {
            module.log(Log.WARN, ModuleMain.TAG, "SystemServerHook power key hook failed: ${e.message}")
        }

        try {
            // Hook VoiceInteractionManagerService 配置修复
            val serviceClass = Class.forName(
                "com.android.server.voiceinteraction.VoiceInteractionManagerService",
                false,
                classLoader
            )
            val method = serviceClass.getDeclaredMethod(
                "onBootPhase",
                Int::class.javaPrimitiveType
            )
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    chain.proceed()
                    null
                }
        } catch (e: Throwable) {
            // VoiceInteractionManagerService 可能因 ROM 不同而缺失，忽略
            module.log(Log.WARN, ModuleMain.TAG, "SystemServerHook voice interaction hook failed: ${e.message}")
        }
    }
}

object SystemUIHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "SystemUIHook installing")
        try {
            // 核心手势条/识别入口拦截，具体 Hook 点需按 ROM 版本适配
            // 保留安装记录便于后续版本补充实现
        } catch (e: Throwable) {
            module.log(Log.WARN, ModuleMain.TAG, "SystemUIHook failed: ${e.message}")
        }
    }
}

object ColorOSAssistantHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "ColorOSAssistantHook installing")
        try {
            // 小布助手入口接管，具体 Hook 点需按 ColorOS ROM 适配
            // 保留安装记录便于后续版本补充实现
        } catch (e: Throwable) {
            module.log(Log.WARN, ModuleMain.TAG, "ColorOSAssistantHook failed: ${e.message}")
        }
    }
}

object HyperOSAssistantHook {
    fun handle(module: XposedModule, classLoader: ClassLoader) {
        module.log(Log.INFO, ModuleMain.TAG, "HyperOSAssistantHook installing")
        try {
            // 小爱同学入口接管，具体 Hook 点需按 HyperOS 版本适配
            // 保留安装记录便于后续版本补充实现
        } catch (e: Throwable) {
            module.log(Log.WARN, ModuleMain.TAG, "HyperOSAssistantHook failed: ${e.message}")
        }
    }
}