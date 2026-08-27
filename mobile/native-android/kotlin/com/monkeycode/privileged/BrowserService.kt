package com.monkeycode.privileged

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
    private val screenshotDir = File(context.filesDir, "browser_screenshots").apply { mkdirs() }

    private class TabSession(val tabId: String, val webView: WebView) {
        var pageTitle = ""
        var pageUrl = ""
        var loaded = false
        var loadError: String? = null
    }

    private fun validateUrl(raw: String) {
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return
        throw IllegalArgumentException("仅支持 http/https 地址")
    }

    private fun ensureTab() {
        if (activeTabId == null || !tabs.containsKey(activeTabId)) {
            val id = UUID.randomUUID().toString().take(8)
            val wv = createWebView()
            tabs[id] = TabSession(id, wv)
            activeTabId = id
        }
    }

    private fun getActiveTab(): TabSession {
        ensureTab()
        return tabs[activeTabId]!!
    }

    private fun createWebView(): WebView {
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val tab = tabs.values.find { it.webView == view }
                tab?.let {
                    it.pageTitle = view.title ?: ""
                    it.pageUrl = url
                    it.loaded = true
                }
            }
        }
        return wv
    }

    private fun runOnMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
    }

    private fun evalJs(js: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        handler.post {
            getActiveTab().webView.evaluateJavascript(js) { json ->
                result = json ?: "null"
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun resolveRef(ref: String): String {
        return refMap[ref] ?: throw IllegalArgumentException("元素引用 $ref 已失效")
    }

    private fun saveAndEncode(bmp: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 80, bos)
        val filename = "screenshot_${System.currentTimeMillis()}.png"
        val file = File(screenshotDir, filename)
        FileOutputStream(file).use { it.write(bos.toByteArray()) }
        val base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return "截图已保存: $filename\n数据: $base64"
    }

    private fun formatSnapshot(json: String, title: String, url: String): String {
        return "页面: $title ($url)\n结构: $json"
    }

    fun browserNavigate(url: String): String {
        validateUrl(url)
        val tab = getActiveTab()
        tab.loaded = false
        tab.loadError = null
        runOnMainSync { tab.webView.loadUrl(url) }
        val start = System.currentTimeMillis()
        while (!tab.loaded && System.currentTimeMillis() - start < 15000) {
            Thread.sleep(200)
        }
        if (!tab.loaded) tab.loadError = "页面加载超时"
        tab.loadError?.let { throw IllegalStateException("导航失败: $it") }
        refMap.clear()
        refCounter.set(0)
        return "已打开: ${tab.pageTitle}\nURL: ${tab.pageUrl}"
    }

    fun browserScreenshot(elementRef: String?): String {
        val tab = getActiveTab()
        val bmp: Bitmap? = if (elementRef != null) {
            val selector = resolveRef(elementRef)
            val rectJson = evalJs(
                "(function(){var el=document.querySelector('$selector');" +
                "if(!el)return'{}';var r=el.getBoundingClientRect();" +
                "return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height})})()"
            )
            try {
                val rect = org.json.JSONObject(rectJson)
                if (rect.has("w")) {
                    val w = (rect.getDouble("w") * tab.webView.scale).toInt().coerceAtLeast(1)
                    val h = (rect.getDouble("h") * tab.webView.scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        val c = Canvas(b)
                        c.translate(-rect.getDouble("x").toFloat(), -rect.getDouble("y").toFloat())
                        tab.webView.draw(c)
                    }
                } else null
            } catch (_: Exception) { null }
        } else {
            val b = Bitmap.createBitmap(tab.webView.width, tab.webView.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            tab.webView.draw(c)
            b
        }
        return saveAndEncode(bmp ?: run {
            val b = Bitmap.createBitmap(tab.webView.width, tab.webView.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            tab.webView.draw(c)
            b
        })
    }

    fun browserSnapshot(): String {
        val tab = getActiveTab()
        val js = "(function(){function c(n,d){if(d>32)return null;" +
            "var r={tag:n.tagName?n.tagName.toLowerCase():'#text',attrs:{}};" +
            "if(n.getAttribute){['id','class','type','placeholder','aria-label','role','href','src','alt','name','value']" +
            ".forEach(function(a){var v=n.getAttribute(a);if(v)r.attrs[a]=v})}" +
            "var rect=n.getBoundingClientRect?n.getBoundingClientRect():null;" +
            "if(rect&&(rect.width>0||rect.height>0))r.rect={x:Math.round(rect.x),y:Math.round(rect.y),w:Math.round(rect.width),h:Math.round(rect.height)};" +
            "if(n.textContent)r.text=n.textContent.trim().substring(0,200);" +
            "if(n.childNodes&&n.childNodes.length){r.children=[];" +
            "for(var i=0;i<n.childNodes.length;i++){var ch=c(n.childNodes[i],d+1);if(ch)r.children.push(ch)}}return r}" +
            "return JSON.stringify(c(document.body||document.documentElement,0))})()"
        return formatSnapshot(evalJs(js), tab.pageTitle, tab.pageUrl)
    }

    fun browserClick(ref: String) {
        val selector = resolveRef(ref)
        evalJs("(function(){var el=document.querySelector('$selector');" +
            "if(el){el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});" +
            "el.click();return'true'}return'false'})()")
    }

    fun browserType(ref: String, text: String) {
        val selector = resolveRef(ref)
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        evalJs("(function(){var el=document.querySelector('$selector');" +
            "if(!el)return'false';el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});" +
            "el.focus();if('value' in el){el.value='$escaped'}else if(el.isContentEditable){el.textContent='$escaped'}" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return'true'})()")
    }

    fun browserScroll(ref: String?) {
        if (ref != null) {
            val selector = resolveRef(ref)
            evalJs("(function(){var el=document.querySelector('$selector');" +
                "if(el){el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});return'true'}return'false'})()")
        } else {
            evalJs("(function(){window.scrollBy({top:innerHeight*0.8,behavior:'instant'});" +
                "return JSON.stringify({y:Math.round(scrollY),docH:Math.round(document.documentElement.scrollHeight),winH:innerHeight})})()")
        }
    }

    fun browserEvaluate(expression: String): String {
        return evalJs(expression)
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
                val wv = createWebView()
                tabs[newId] = TabSession(newId, wv)
                activeTabId = newId
                wv.loadUrl("about:blank")
                return "已新建标签页 #$newId"
            }
            "close" -> {
                val id = tabId ?: activeTabId ?: return "没有可关闭的标签页"
                if (tabs.size <= 1) return "无法关闭最后一个标签页"
                tabs.remove(id)?.webView?.destroy()
                if (activeTabId == id) activeTabId = tabs.keys.firstOrNull()
                return "已关闭标签页 #$id"
            }
            "switch" -> {
                val id = tabId ?: return "请指定标签页 ID"
                if (!tabs.containsKey(id)) return "标签页 #$id 不存在"
                activeTabId = id
                return "已切换到标签页 #$id"
            }
            else -> return "未知操作: $action (支持 list/create/close/switch)"
        }
    }

    fun browserDialog(action: String): String {
        val tab = getActiveTab()
        return when (action) {
            "accept" -> { evalJs("window.confirm=function(){return true};window.alert=function(){};window.prompt=function(){return''};'accept'"); "已接受对话框" }
            "dismiss" -> { evalJs("window.confirm=function(){return false};window.alert=function(){};window.prompt=function(){return null};'dismiss'"); "已关闭对话框" }
            else -> "未知操作: $action (支持 accept/dismiss)"
        }
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