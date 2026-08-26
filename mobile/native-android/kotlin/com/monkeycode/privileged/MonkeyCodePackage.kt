package com.monkeycode.privileged

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MonkeyCodePackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
    val modules = mutableListOf<NativeModule>()
    try {
      modules.add(PrivilegedExecutionModule(reactContext))
    } catch (e: Throwable) {
      android.util.Log.e("MonkeyCode", "Failed to init PrivilegedExecutionModule: ${e.message}")
    }
    return modules
  }

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
    return emptyList()
  }
}