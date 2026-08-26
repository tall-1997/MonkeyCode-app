package com.monkeycode.privileged

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.facebook.react.bridge.*
import java.io.ByteArrayOutputStream
import java.io.File

class MonkeyCodeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MonkeyCodeAccessibilityService? = null
        var onTreeChanged: ((String) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            rootInActiveWindow?.let { root ->
                onTreeChanged?.invoke(nodeToJson(root))
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun getTreeJson(): String {
        val root = rootInActiveWindow ?: return "{}"
        return nodeToJson(root)
    }

    fun performClickAt(x: Float, y: Float): Boolean {
        val gestureBuilder = GestureDescription.Builder()
        val path = Path().apply {
            moveTo(x, y)
        }
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 1))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performSwipeAt(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300): Boolean {
        val gestureBuilder = GestureDescription.Builder()
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performInputText(text: String) {
        // 通过剪贴板粘贴
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("monkeycode_input", text)
        clipboard.setPrimaryClip(clip)

        // 查找当前焦点输入框并粘贴
        rootInActiveWindow?.let { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"className\":\"${node.className}\",")
        sb.append("\"text\":\"${node.text?.toString()?.replace("\"", "\\\"")}\",")
        sb.append("\"contentDescription\":\"${node.contentDescription?.toString()?.replace("\"", "\\\"")}\",")
        sb.append("\"resourceId\":\"${node.viewIdResourceName}\",")
        sb.append("\"isClickable\":${node.isClickable},")
        sb.append("\"isEditable\":${node.isEditable},")
        sb.append("\"isEnabled\":${node.isEnabled},")
        sb.append("\"isFocused\":${node.isFocused},")
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        sb.append("\"bounds\":{\"left\":${bounds.left},\"top\":${bounds.top},\"right\":${bounds.right},\"bottom\":${bounds.bottom}},")
        sb.append("\"children\":[")
        for (i in 0 until node.childCount) {
            if (i > 0) sb.append(",")
            val child = node.getChild(i)
            if (child != null) {
                sb.append(nodeToJson(child))
                child.recycle()
            }
        }
        sb.append("]}")
        return sb.toString()
    }
}

class GUIAgent(private val context: Context) {
    private var overlayView: View? = null
    private var isRunning: Boolean = false

    fun takeScreenshot(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw IllegalStateException("Screenshot failed: ${e.message}")
        }
    }

    fun getAccessibilityTree(): String {
        val service = MonkeyCodeAccessibilityService.instance
            ?: throw IllegalStateException("AccessibilityService not connected")
        return service.getTreeJson()
    }

    fun performClick(x: Float, y: Float) {
        val service = MonkeyCodeAccessibilityService.instance
        if (service != null) {
            if (!service.performClickAt(x, y)) {
                // Fallback to input tap
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
            }
        } else {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
        }
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val service = MonkeyCodeAccessibilityService.instance
        if (service != null) {
            if (!service.performSwipeAt(x1, y1, x2, y2)) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x1 $y1 $x2 $y2"))
            }
        } else {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x1 $y1 $x2 $y2"))
        }
    }

    fun performInput(text: String) {
        val service = MonkeyCodeAccessibilityService.instance
        if (service != null) {
            service.performInputText(text)
        } else {
            // Fallback: use clipboard + input keyevent
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("monkeycode_input", text)
            clipboard.setPrimaryClip(clip)
            // Simulate paste via keyevent
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 279")) // KEYCODE_PASTE
        }
    }

    fun stopOperation() {
        isRunning = false
        hideOverlay()
    }

    fun showOverlay(text: String) {
        if (overlayView != null) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        val textView = android.widget.TextView(context).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            setPadding(32, 16, 32, 16)
            textSize = 14f
        }

        overlayView = textView
        windowManager.addView(textView, overlayParams)
    }

    fun hideOverlay() {
        val view = overlayView ?: return
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(view)
        } catch (_: Exception) {}
        overlayView = null
    }
}