package com.monkeycode.hook

import android.util.Log

/**
 * Hook 安装注册与诊断（对齐 Eta HookRegistrar）：
 *  - 每个 Hook 使用稳定 ID
 *  - 安装结果区分 INSTALLED / MISSING / FAILED / SKIPPED
 *  - 目标签名漂移（ROM / App 升级）时记录缺失，便于定位
 */
object HookRegistrar {

    enum class Result { INSTALLED, MISSING, FAILED, SKIPPED }

    private val results = mutableMapOf<String, Result>()

    @Synchronized
    fun record(id: String, result: Result, detail: String = "") {
        results[id] = result
        when (result) {
            Result.INSTALLED -> Log.i(ModuleMain.TAG, "[$id] installed")
            Result.MISSING -> Log.w(ModuleMain.TAG, "[$id] missing target: $detail")
            Result.FAILED -> Log.e(ModuleMain.TAG, "[$id] failed: $detail")
            Result.SKIPPED -> Log.i(ModuleMain.TAG, "[$id] skipped")
        }
    }

    @Synchronized
    fun summary(): String = results.entries.joinToString(", ") { "${it.key}=${it.value}" }

    @Synchronized
    fun has(id: String): Boolean = results[id] == Result.INSTALLED
}

/** 安全反射工具：目标类可能因 ROM 版本不同而缺失。 */
internal object Reflect {
    fun tryClass(name: String, loader: ClassLoader): Class<*>? {
        return try { Class.forName(name, false, loader) } catch (e: Throwable) { null }
    }

    fun tryMethod(cls: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method? {
        return try { cls.getDeclaredMethod(name, *params) } catch (e: Throwable) { null }
    }
}