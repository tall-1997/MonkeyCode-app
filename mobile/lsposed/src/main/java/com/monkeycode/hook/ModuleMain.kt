package com.monkeycode.hook

import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModuleMain : XposedModule() {
    private var processName: String? = null
    private var systemServer = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        systemServer = param.isSystemServer

        if (!TargetGate.acceptProcess(param.processName, param.isSystemServer)) {
            log(Log.INFO, TAG, "process rejected: ${param.processName}")
            detach()
            return
        }

        log(
            Log.INFO,
            TAG,
            "module ready: process=${param.processName}, api=$apiVersion, framework=$frameworkName/$frameworkVersion",
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val process = processName ?: return
        if (!systemServer || process != TargetGate.SYSTEM_SERVER_PROCESS) {
            HookRegistrar.record("system.lifecycle", InstallState.REJECTED, "process gate")
            return
        }
        DiagnosticHooks.installSystemServer(this, param.classLoader)
        reportInstall(process)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val process = processName ?: return
        val version = TargetGate.compileSdkVersion(param.applicationInfo)
        val rejection = TargetGate.rejection(param.packageName, process, param.applicationInfo)
        if (rejection != null) {
            HookRegistrar.record("${param.packageName}.lifecycle", InstallState.REJECTED, rejection)
            return
        }

        DiagnosticHooks.installApplicationLifecycle(this, param.packageName, param.classLoader)
        reportInstall(process, param.packageName, version)
    }

    private fun reportInstall(process: String, packageName: String = "system", versionCode: Int = 0) {
        log(
            Log.INFO,
            TAG,
            "install report: package=$packageName, process=$process, version=$versionCode, ${HookRegistrar.summary()}",
        )
    }

    companion object {
        const val TAG = "MonkeyCodeSafeHook"
    }
}

private object TargetGate {
    const val SYSTEM_SERVER_PROCESS = "system_server"

    private val packages = setOf(
        "com.android.systemui",
        "com.google.android.googlequicksearchbox",
        "com.coloros.colordirectservice",
        "com.heytap.speechassist",
        "com.oplus.aimemory",
        "com.miui.voiceassist",
    )

    fun acceptProcess(processName: String, isSystemServer: Boolean): Boolean {
        if (isSystemServer) return processName == SYSTEM_SERVER_PROCESS
        return packages.any { processName == it || processName.startsWith("$it:") }
    }

    fun rejection(
        packageName: String,
        processName: String,
        applicationInfo: ApplicationInfo,
    ): String? {
        if (packageName !in packages) return "package gate"
        if (processName != packageName && !processName.startsWith("$packageName:")) return "process gate"
        if (applicationInfo.minSdkVersion < 26) return "version gate: minSdk below 26"
        if (applicationInfo.targetSdkVersion !in 26..37) return "version gate: targetSdk outside 26..37"
        val compileSdk = compileSdkVersion(applicationInfo)
        if (compileSdk != 0 && compileSdk !in 26..37) return "version gate: compileSdk outside 26..37"
        if (applicationInfo.sourceDir.isNullOrBlank()) return "version gate: missing source APK"
        return null
    }

    fun compileSdkVersion(applicationInfo: ApplicationInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applicationInfo.compileSdkVersion else 0
}

private object DiagnosticHooks {
    fun installApplicationLifecycle(module: XposedModule, packageName: String, loader: ClassLoader) {
        HookRegistrar.install(
            module = module,
            id = "$packageName.application.onCreate",
            executable = Reflect.method("android.app.Application", loader, "onCreate"),
        ) { chain ->
            module.log(Log.DEBUG, ModuleMain.TAG, "lifecycle observed: $packageName Application.onCreate")
            chain.proceed()
        }
    }

    fun installSystemServer(module: XposedModule, loader: ClassLoader) {
        HookRegistrar.install(
            module = module,
            id = "system.lifecycle.startBootstrapServices",
            executable = Reflect.method("com.android.server.SystemServer", loader, "startBootstrapServices"),
        ) { chain ->
            module.log(Log.DEBUG, ModuleMain.TAG, "lifecycle observed: system bootstrap")
            chain.proceed()
        }
    }
}
