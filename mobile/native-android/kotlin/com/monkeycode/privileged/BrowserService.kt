package com.monkeycode.privileged

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserService(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val tabs = ConcurrentHashMap<String, TabSession>()
    private var activeTabId: String? = null
    private val refMap = ConcurrentHashMap<String, String>()
    private val refCounter = AtomicInteger(0)
    private val screenshotDir: File = File(context.filesDir, "browser_screenshots").apply { mkdirs() }

    private class TabSession(
        val tabId: String,
        val webView: WebView
    ) {
        var pageTitle: String = ""
        var pageUrl: String = ""
        val loaded = CountDownLatch(1)
        var loadError: String? = null
    }

    fun browserNavigate(url: String): String {
        validateUrl(url)
        val result = StringBuilder()
        runOnMainSync {
            ensureTab()
            val tab = tabs[activeTabId]!!
            tab.loaded.countDown()
            tab.webView.loadUrl(url)
        }
        val tab = tabs[activeTabId]!!
        val ok = tab.loaded.await(15, TimeUnit.SECONDS)
        if (!ok) {
            tab.loadError = "页面加载超时"
        }
        val err = tab.loadError
        if (err != null) throw IllegalStateException("导航失败: $err")
        result.append("已打开: ${tab.pageTitle}\nURL: ${tab.pageUrl}")
        refMap.clear()
        refCounter.set(0)
        return result.toString()
    }

    fun browserScreenshot(elementRef: String?): String {
        val tab = getActiveTab()
        val bitmap = runOnMainSync {
            if (elementRef != null) {
                val selector = resolveRef(elementRef)
                val js = """
                    (function() {
                        var el = document.querySelector('${selector}');
                        if (!el) return null;
                        var r = el.getBoundingClientRect();
                        return JSON.stringify({x: r.x, y: r.y, w: r.width, h: r.height});
                    })()
                """.trimIndent()
                tab.webView.evaluateJavascript(js) { json ->
                    tab.webView.post {
                        val rect = org.json.JSONObject(json ?: "{}")
                        val x = rect.optInt("x", 0)
                        val y = rect.optInt("y", 0)
                        val w = rect.optInt("w", tab.webView.width)
                        val h = rect.optInt("h", tab.webView.height)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.translate(-x.toFloat(), -y.toFloat())
                        tab.webView.draw(canvas)
                        saveAndEncode(bmp)
                    }
                }
                null
            } else {
                val bmp = Bitmap.createBitmap(tab.webView.width, tab.webView.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                tab.webView.draw(canvas)
                bmp
            }
        }
        if (bitmap == null) {
            val bmp = Bitmap.createBitmap(tab.webView.width, tab.webView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            tab.webView.draw(canvas)
            return saveAndEncode(bmp)
        }
        return saveAndEncode(bitmap)
    }

    fun browserSnapshot(): String {
        val tab = getActiveTab()
        val js = """
(function() {
  function collect(node, depth) {
    if (depth > 32) return null;
    var r = { tag: node.tagName ? node.tagName.toLowerCase() : '#text', attrs: {} };
    if (node.getAttribute) {
      ['id','class','type','placeholder','aria-label','role','href','src','alt','name','value'].forEach(function(a) {
        var v = node.getAttribute(a);
        if (v) r.attrs[a] = v;
      });
    }
    var rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
    if (rect && (rect.width > 0 || rect.height > 0)) r.rect = { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) };
    if (node.textContent) r.text = node.textContent.trim().substring(0, 200);
    if (node.childNodes && node.childNodes.length) {
      r.children = [];
      for (var i = 0; i < node.childNodes.length; i++) {
        var c = collect(node.childNodes[i], depth + 1);
        if (c) r.children.push(c);
      }
    }
    return r;
  }
  return JSON.stringify(collect(document.body || document.documentElement, 0));
})()
        """.trimIndent()
        val latch = CountDownLatch(1)
        var result = ""
        handler.post {
            tab.webView.evaluateJavascript(js) { json ->
                result = json ?: "{}"
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return formatSnapshot(result, tab.pageTitle, tab.pageUrl)
    }

    fun browserClick(ref: String) {
        val selector = resolveRef(ref)
        val tab = getActiveTab()
        handler.post {
            tab.webView.evaluateJavascript(
                "(function(){var el=document.querySelector('${selector}');if(el){el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});el.click();return true;}return false;})()",
                null
            )
        }
    }

    fun browserType(ref: String, text: String) {
        val selector = resolveRef(ref)
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        val tab = getActiveTab()
        handler.post {
            tab.webView.evaluateJavascript(
                "(function(){var el=document.querySelector('${selector}');if(!el)return false;el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});el.focus();if('value' in el){el.value='${escaped}'}else if(el.isContentEditable){el.textContent='${escaped}'}el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return true;})()",
                null
            )
        }
    }

    fun browserScroll(ref: String?) {
        val tab = getActiveTab()
        if (ref != null) {
            val selector = resolveRef(ref)
            handler.post {
                tab.webView.evaluateJavascript(
                    "(function(){var el=document.querySelector('${selector}');if(el){el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});return true;}return false;})()",
                    null
                )
            }
        } else {
            handler.post {
                tab.webView.evaluateJavascript(
                    "(function(){window.scrollBy({top:innerHeight*0.8,behavior:'instant'});return {y:Math.round(scrollY),docH:Math.round(document.documentElement.scrollHeight),winH:innerHeight};})()",
                    null
                )
            }
        }
    }

    fun browserEvaluate(expression: String): String {
        val tab = getActiveTab()
        val escaped = expression.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\${")
        val latch = CountDownLatch(1)
        var result = ""
        handler.post {
            tab.webView.evaluateJavascript(escaped) { json ->
                result = json ?: "null"
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    fun browserTabs(action: String, tabId: String?): String {
        when (action) {
            "list" -> {
                val sb = StringBuilder("标签页(${tabs.size} 个):\n")
                tabs.forEach { (id, t) ->
                    val marker = if (id == activeTabId) "[当前]" else ""
                    sb.append("#$id $marker ${t.pageTitle} — ${t.pageUrl}\n")
                }
                return sb.toString().trimEnd()
            }
            "create" -> {
                val newId = UUID.randomUUID().toString().take(8)
                runOnMainSync {
                    val wv = createWebView()
                    tabs[newId] = TabSession(newId, wv)
                    activeTabId = newId
                    wv.loadUrl("about:blank")
                }
                return "已新建标签页 #$newId"
            }
            "close" -> {
                val closeId = tabId ?: activeTabId ?: throw IllegalStateException("没有可关闭的标签页")
                if (tabs.size <= 1) return "无法关闭最后一个标签页"
                tabs.remove(closeId)
                if (activeTabId == closeId) {
                    activeTabId = tabs.keys.firstOrNull()
                }
                return "已关闭标签页 #$closeId"
            }
            "switch" -> {
                val switchId = tabId ?: throw IllegalStateException("switch 需要 tabId")
                if (!tabs.containsKey(switchId)) throw IllegalStateException("标签页 #$switchId 不存在")
                activeTabId = switchId
                val tab = tabs[switchId]!!
                return "已切换到标签页 #$switchId: ${tab.pageTitle} — ${tab.pageUrl}"
            }
            else -> throw IllegalStateException("未知 action: $action (支持 list/create/close/switch)")
        }
    }

    fun browserDialog(action: String): String {
        val tab = getActiveTab()
        when (action) {
            "accept" -> {
                handler.post {
                    tab.webView.evaluateJavascript("(function(){if(window.alert)window.alert=function(){};if(window.confirm)window.confirm=function(){return true;};if(window.prompt)window.prompt=function(){return '';};return true;})()", null)
                }
                return "已接受对话框"
            }
            "dismiss" -> {
                handler.post {
                    tab.webView.evaluateJavascript("(function(){if(window.alert)window.alert=function(){};if(window.confirm)window.confirm=function(){return false;};if(window.prompt)window.prompt=function(){return null;};return true;})()", null)
                }
                return "已关闭对话框"
            }
            else -> throw IllegalStateException("未知 action: $action (支持 accept/dismiss)")
        }
    }

    private fun resolveRef(ref: String): String {
        return refMap[ref] ?: throw IllegalStateException("元素引用 $ref 已失效,请重新 browser_snapshot")
    }

    private fun validateUrl(url: String) {
        if (url == "about:blank") return
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw IllegalStateException("仅支持 http/https 地址(收到 \"$url\")")
        }
    }

    private fun ensureTab() {
        if (activeTabId == null || !tabs.containsKey(activeTabId)) {
            val newId = UUID.randomUUID().toString().take(8)
            val wv = createWebView()
            tabs[newId] = TabSession(newId, wv)
            activeTabId = newId
        }
    }

    private fun getActiveTab(): TabSession {
        ensureTab()
        return tabs[activeTabId] ?: throw IllegalStateException("没有活动标签页")
    }

    private fun createWebView(): WebView {
        return runOnMainSync {
            val wv = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val tab = tabs.values.find { it.webView == view }
                        tab?.let {
                            it.pageUrl = url ?: ""
                            it.pageTitle = view?.title ?: ""
                            it.loadError = null
                            it.loaded.countDown()
                        }
                    }
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        val tab = tabs.values.find { it.webView == view }
                        tab?.let {
                            it.loadError = description ?: "加载失败"
                        }
                    }
                }
                webChromeClient = WebChromeClient()
                addJavascriptInterface(BrowserJsBridge(), "MonkeyCodeBrowser")
            }
            wv
        }
    }

    inner class BrowserJsBridge {
        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("MonkeyCode.Browser", message)
        }
    }

    private fun formatSnapshot(json: String, title: String, url: String): String {
        val sb = StringBuilder()
        sb.append("页面: ${title.ifBlank { "(无标题)" }}\n")
        sb.append("URL: $url\n\n")
        sb.append("可交互元素:\n")
        try {
            val root = org.json.JSONObject(json)
            refMap.clear()
            refCounter.set(0)
            collectInteractive(root, sb, 0)
        } catch (e: Exception) {
            sb.append("(解析快照失败: ${e.message})")
        }
        sb.append("\n(调用 browser_click/browser_type 按编号操作元素)")
        return sb.toString()
    }

    private fun collectInteractive(node: org.json.JSONObject, sb: StringBuilder, depth: Int) {
        if (depth > 16) return
        val tag = node.optString("tag", "")
        val attrs = node.optJSONObject("attrs") ?: org.json.JSONObject()
        val text = node.optString("text", "")
        val rect = node.optJSONObject("rect")
        val children = node.optJSONArray("children")

        val interactiveTags = setOf("a", "button", "input", "select", "textarea", "option")
        val isInteractive = tag in interactiveTags ||
            attrs.optString("role", "").isNotEmpty() ||
            attrs.optString("href", "").isNotEmpty()

        if (isInteractive && rect != null) {
            val idx = refCounter.incrementAndGet()
            val ref = "e$idx"
            val selector = buildSelector(tag, attrs)
            refMap[ref] = selector
            val indent = "  ".repeat(depth.coerceAtMost(8))
            val label = when (tag) {
                "a" -> "链接"
                "button" -> "按钮"
                "input" -> {
                    val type = attrs.optString("type", "text")
                    when (type) {
                        "text", "password", "email", "number" -> "输入框"
                        "submit" -> "提交按钮"
                        "checkbox" -> "复选框"
                        "radio" -> "单选"
                        else -> "输入"
                    }
                }
                "select" -> "下拉框"
                "textarea" -> "文本域"
                "option" -> "选项"
                else -> "元素"
            }
            val desc = buildString {
                append(text.take(60))
                val placeholder = attrs.optString("placeholder", "")
                if (placeholder.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("($placeholder)")
                }
                val name = attrs.optString("name", "")
                if (name.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("[name=$name]")
                }
                val id = attrs.optString("id", "")
                if (id.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("#$id")
                }
            }
            sb.append("$indent$ref $label \"$desc\"\n")
        }

        if (children != null) {
            for (i in 0 until children.length()) {
                collectInteractive(children.getJSONObject(i), sb, depth + 1)
            }
        }
    }

    private fun buildSelector(tag: String, attrs: org.json.JSONObject): String {
        val id = attrs.optString("id", "")
        if (id.isNotEmpty()) return "#$id"
        val name = attrs.optString("name", "")
        if (name.isNotEmpty()) return "$tag[name='$name']"
        val type = attrs.optString("type", "")
        val placeholder = attrs.optString("placeholder", "")
        val ariaLabel = attrs.optString("aria-label", "")
        if (placeholder.isNotEmpty()) return "$tag[placeholder='$placeholder']"
        if (ariaLabel.isNotEmpty()) return "$tag[aria-label='$ariaLabel']"
        val cls = attrs.optString("class", "")
        if (cls.isNotEmpty()) return "$tag.${cls.split(" ").joinToString(".")}"
        return tag
    }

    private fun saveAndEncode(bitmap: Bitmap): String {
        val filename = "browser-${System.currentTimeMillis()}.png"
        val file = File(screenshotDir, filename)
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, bos)
        FileOutputStream(file).use { it.write(bos.toByteArray()) }
        val base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return "截图已保存: $filename\n数据: $base64"
    }

    private fun <T> runOnMainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val holder = arrayOf<Any?>(null)
        val latch = CountDownLatch(1)
        handler.post {
            holder[0] = block()
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    fun destroy() {
        handler.post {
            tabs.values.forEach { it.webView.destroy() }
            tabs.clear()
            activeTabId = null
            refMap.clear()
        }
    }
}