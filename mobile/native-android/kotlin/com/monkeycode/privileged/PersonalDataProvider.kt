package com.monkeycode.privileged

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.*
import com.facebook.react.bridge.*
import java.io.File

class PersonalDataProvider(private val context: Context) {

    fun queryGallery(limit: Int): WritableArray {
        val array = Arguments.createArray()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        val selection = "${MediaStore.Images.Media.SIZE} > 0"

        context.contentResolver.query(uri, projection, selection, null, "$sortOrder LIMIT $limit")
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val map = Arguments.createMap()
                    map.putString("id", cursor.getString(0))
                    map.putString("name", cursor.getString(1))
                    map.putString("path", cursor.getString(2))
                    map.putDouble("dateModified", cursor.getLong(3).toDouble())
                    map.putDouble("size", cursor.getLong(4).toDouble())
                    map.putInt("width", cursor.getInt(5))
                    map.putInt("height", cursor.getInt(6))
                    array.pushMap(map)
                }
            }
        return array
    }

    fun queryCalendar(startTime: Long, endTime: Long): WritableArray {
        val array = Arguments.createArray()
        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val map = Arguments.createMap()
                    map.putString("id", cursor.getString(0))
                    map.putString("title", cursor.getString(1) ?: "")
                    map.putString("description", cursor.getString(2) ?: "")
                    map.putDouble("startTime", cursor.getLong(3).toDouble())
                    map.putDouble("endTime", cursor.getLong(4).toDouble())
                    map.putString("location", cursor.getString(5) ?: "")
                    array.pushMap(map)
                }
            }
        return array
    }

    fun querySMS(limit: Int): WritableArray {
        val array = Arguments.createArray()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"

        context.contentResolver.query(uri, projection, null, null, sortOrder)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val map = Arguments.createMap()
                    map.putString("id", cursor.getString(0))
                    map.putString("address", cursor.getString(1) ?: "")
                    map.putString("body", cursor.getString(2) ?: "")
                    map.putDouble("date", cursor.getLong(3).toDouble())
                    val type = cursor.getInt(4)
                    map.putString("type", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent")
                    array.pushMap(map)
                }
            }
        return array
    }

    fun queryNotifications(limit: Int): WritableArray {
        val array = Arguments.createArray()
        // 从系统通知历史中读取
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "dumpsys notification --noredact | grep -A5 'NotificationRecord' | head -$limit")
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // 解析通知记录
            val lines = output.lines()
            var currentNotification = Arguments.createMap()
            for (line in lines) {
                when {
                    line.contains("pkg=") -> {
                        if (currentNotification.hasKey("package")) {
                            array.pushMap(currentNotification)
                            currentNotification = Arguments.createMap()
                        }
                        currentNotification.putString("package", line.substringAfter("pkg=").trim())
                    }
                    line.contains(" Notification(") -> {
                        currentNotification.putString("title", line.substringAfter("Notification(").substringBefore(")").trim())
                    }
                }
            }
            if (currentNotification.hasKey("package")) {
                array.pushMap(currentNotification)
            }
        } catch (_: Exception) {
            // 通知历史不可用
        }
        return array
    }

    fun queryAppUsage(limit: Int): WritableArray {
        val array = Arguments.createArray()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 7 * 24 * 60 * 60 * 1000L // 最近 7 天

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            ?: return array

        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        )

        stats
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .forEach { stat ->
                val map = Arguments.createMap()
                map.putString("packageName", stat.packageName)
                map.putDouble("totalTimeInForeground", stat.totalTimeInForeground.toDouble())
                map.putDouble("lastTimeUsed", stat.lastTimeUsed.toDouble())
                array.pushMap(map)
            }
        return array
    }

    fun getLocation(): WritableMap {
        val map = Arguments.createMap()
        try {
            // 通过 Root 读取最近位置
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "dumpsys location | grep -A2 'Last Known Location' | tail -2")
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val lines = output.lines()
            for (line in lines) {
                if (line.contains("Latitude")) {
                    map.putDouble("latitude", line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0)
                }
                if (line.contains("Longitude")) {
                    map.putDouble("longitude", line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0)
                }
            }
        } catch (_: Exception) {
            map.putNull("latitude")
            map.putNull("longitude")
        }
        return map
    }
}