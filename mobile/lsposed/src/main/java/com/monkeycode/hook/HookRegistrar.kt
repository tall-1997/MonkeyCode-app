package com.monkeycode.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal enum class InstallState { INSTALLED, MISSING, FAILED, REJECTED }

internal object HookRegistrar {
    private const val DIAGNOSTIC_PRIORITY = XposedInterface.PRIORITY_LOWEST + 102
    private val handles = ConcurrentHashMap<String, XposedInterface.HookHandle>()
    private val reports = ConcurrentHashMap<String, String>()

    fun install(
        module: XposedModule,
        id: String,
        executable: Executable?,
        hooker: XposedInterface.Hooker,
    ) {
        if (executable == null) {
            record(id, InstallState.MISSING, "target signature unavailable; native behavior retained")
            return
        }

        try {
            val handle = module.hook(executable)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setPriority(DIAGNOSTIC_PRIORITY)
                .intercept(hooker)
            handles[id] = handle
            record(id, InstallState.INSTALLED, "priority=$DIAGNOSTIC_PRIORITY, mode=PROTECTIVE")
        } catch (error: Throwable) {
            record(id, InstallState.FAILED, "${error.javaClass.simpleName}; native behavior retained")
            module.log(Log.ERROR, ModuleMain.TAG, "hook installation failed: $id", error)
        }
    }

    fun record(id: String, state: InstallState, detail: String = "") {
        reports[id] = if (detail.isEmpty()) state.name else "${state.name}($detail)"
        val priority = if (state == InstallState.FAILED) Log.ERROR else Log.INFO
        Log.println(priority, ModuleMain.TAG, "[$id] ${reports[id]}")
    }

    fun summary(): String = reports.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
}

internal object Reflect {
    fun method(className: String, loader: ClassLoader, methodName: String): Method? = try {
        Class.forName(className, false, loader).getDeclaredMethod(methodName)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
