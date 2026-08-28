package com.monkeycode.hook

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ModuleApplication : Application() {
    private var service: XposedService? = null
    private var preferences: SharedPreferences? = null

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(boundService: XposedService) {
            service = boundService
            preferences = runCatching { boundService.getRemotePreferences(PREFERENCES_GROUP) }
                .onFailure { Log.e(ModuleMain.TAG, "RemotePreferences unavailable", it) }
                .getOrNull()
            writeServiceReport(boundService)
        }

        override fun onServiceDied(deadService: XposedService) {
            if (service === deadService) {
                service = null
                preferences = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(serviceListener)
    }

    private fun writeServiceReport(boundService: XposedService) {
        runCatching {
            val runningTargets = if (boundService.apiVersion >= XposedService.API_102) {
                boundService.runningTargets.joinToString(",") { it.processName }
            } else {
                ""
            }
            preferences?.edit()
                ?.putInt("service_api", boundService.apiVersion)
                ?.putString("framework_name", boundService.frameworkName)
                ?.putString("framework_version", boundService.frameworkVersion)
                ?.putStringSet("approved_scope", boundService.scope.toSet())
                ?.putString("running_targets", runningTargets)
                ?.putLong("report_timestamp_ms", System.currentTimeMillis())
                ?.apply()
        }.onFailure { Log.e(ModuleMain.TAG, "service report failed", it) }
    }

    companion object {
        const val PREFERENCES_GROUP = "module_settings"
    }
}
