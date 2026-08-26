package com.monkeycode.hook

import android.content.SharedPreferences
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ModuleMain : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private var prefs: SharedPreferences? = null

        fun getPrefs(): SharedPreferences? = prefs

        fun isEnabled(key: String): Boolean {
            return prefs?.getBoolean(key, false) ?: false
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // 初始化 RemotePreferences
        try {
            prefs = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("org.lsposed.lspd.service.XposedService", null),
                "getRemotePreferences",
                "com.monkeycode.hook"
            ) as? SharedPreferences
        } catch (e: Exception) {
            XposedBridge.log("[MonkeyCode] Failed to init RemotePreferences: ${e.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        // 过滤无关进程
        if (lpparam.processName != packageName && !lpparam.processName.endsWith(":core")) {
            return
        }

        when (packageName) {
            "android" -> {
                // system_server: 电源键接管、数字助理配置
                if (isEnabled("system_hook_enabled")) {
                    SystemServerHook.handle(lpparam)
                }
            }

            "com.android.systemui" -> {
                // SystemUI: 手势条拦截
                if (isEnabled("system_hook_enabled")) {
                    SystemUIHook.handle(lpparam)
                }
            }

            // 厂商助手
            "com.coloros.speechassist" -> {
                if (isEnabled("assistant_hook_enabled")) {
                    ColorOSAssistantHook.handle(lpparam)
                }
            }

            "com.miui.voiceassist" -> {
                if (isEnabled("assistant_hook_enabled")) {
                    HyperOSAssistantHook.handle(lpparam)
                }
            }
        }
    }
}

object SystemServerHook {
    fun handle(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook 电源键处理
            val phoneWindowManagerClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                phoneWindowManagerClass,
                "interceptPowerKeyDown",
                "android.view.KeyEvent",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled("system_hook_enabled")) return
                        // 电源键按下时，检查是否应路由到 MonkeyCode 助手
                        XposedBridge.log("[MonkeyCode] Power key intercepted")
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("[MonkeyCode] SystemServerHook failed: ${e.message}")
        }
    }
}

object SystemUIHook {
    fun handle(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook 手势条长按
            XposedBridge.log("[MonkeyCode] SystemUIHook installed")
        } catch (e: Exception) {
            XposedBridge.log("[MonkeyCode] SystemUIHook failed: ${e.message}")
        }
    }
}

object ColorOSAssistantHook {
    fun handle(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("[MonkeyCode] ColorOSAssistantHook installed")
            // 小布助手入口接管
            // 具体 Hook 点需要根据 ROM 版本适配
        } catch (e: Exception) {
            XposedBridge.log("[MonkeyCode] ColorOSAssistantHook failed: ${e.message}")
        }
    }
}

object HyperOSAssistantHook {
    fun handle(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("[MonkeyCode] HyperOSAssistantHook installed")
            // 小爱同学入口接管
            // 具体 Hook 点需要根据 ROM 版本适配
        } catch (e: Exception) {
            XposedBridge.log("[MonkeyCode] HyperOSAssistantHook failed: ${e.message}")
        }
    }
}

private fun isEnabled(key: String): Boolean {
    return ModuleMain.getPrefs()?.getBoolean(key, false) ?: false
}