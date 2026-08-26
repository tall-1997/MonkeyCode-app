package com.monkeycode.privileged

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.facebook.react.bridge.*
import java.io.File

class DeviceTools(private val context: Context) {

    fun setAlarm(hour: Int, minute: Int, label: String) {
        val cmd = "content insert --uri content://com.android.deskclock/alarm " +
                "--bind hour:i:$hour " +
                "--bind minutes:i:$minute " +
                "--bind message:s:'$label' " +
                "--bind enabled:i:1"
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
    }

    fun mediaControl(action: String) {
        val keyCode = when (action.lowercase()) {
            "play" -> 126
            "pause" -> 127
            "play_pause" -> 85
            "next" -> 87
            "previous" -> 88
            "stop" -> 86
            else -> throw IllegalArgumentException("Unknown media action: $action")
        }
        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode"))
    }

    fun setVolume(stream: String, level: Int) {
        val volumeStream = when (stream.lowercase()) {
            "media", "music" -> "3"
            "ring", "ringer" -> "2"
            "alarm" -> "4"
            "notification", "notif" -> "5"
            "voice", "call" -> "0"
            else -> throw IllegalArgumentException("Unknown stream: $stream")
        }
        val clampedLevel = level.coerceIn(0, getMaxVolume(stream))
        Runtime.getRuntime().exec(arrayOf("su", "-c", "media volume --stream $volumeStream --set $clampedLevel"))
    }

    fun toggleWifi(enable: Boolean) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled = enable
        } catch (_: Exception) {
            val state = if (enable) "enable" else "disable"
            Runtime.getRuntime().exec(arrayOf("su", "-c", "svc wifi $state"))
        }
    }

    fun toggleBluetooth(enable: Boolean) {
        val state = if (enable) "enable" else "disable"
        Runtime.getRuntime().exec(arrayOf("su", "-c", "service call bluetooth_manager ${
            if (enable) "1" else "2"
        }"))
    }

    fun getDeviceStatus(): WritableMap {
        val map = Arguments.createMap()

        // 电池
        val batteryIntent = context.registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (scale > 0) (level * 100 / scale) else -1
            map.putInt("battery", batteryPct)
            val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            map.putBoolean("charging", plugged != 0)
        }

        // 存储
        val dataDir = Environment.getDataDirectory()
        val statFs = StatFs(dataDir.path)
        val availableBytes = statFs.availableBytes
        val totalBytes = statFs.totalBytes
        map.putDouble("storageAvailableMB", availableBytes / (1024.0 * 1024.0))
        map.putDouble("storageTotalMB", totalBytes / (1024.0 * 1024.0))

        // 内存
        val memInfo = android.app.ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        activityManager.getMemoryInfo(memInfo)
        map.putDouble("memoryAvailableMB", memInfo.availMem / (1024.0 * 1024.0))
        map.putDouble("memoryTotalMB", memInfo.totalMem / (1024.0 * 1024.0))

        // Wi-Fi
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        map.putBoolean("wifiEnabled", wifiManager?.isWifiEnabled ?: false)

        return map
    }

    private fun getMaxVolume(stream: String): Int {
        return when (stream.lowercase()) {
            "media", "music" -> 15
            "ring", "ringer" -> 7
            "alarm" -> 7
            "notification", "notif" -> 7
            "voice", "call" -> 5
            else -> 15
        }
    }
}