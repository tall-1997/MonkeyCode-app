package com.monkeycode.privileged

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BrowserService(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationLock = Any()
    private val destroyed = AtomicBoolean(false)
    private val tabs = LinkedHashMap<String, TabSession>()
    private val refs = BrowserRefRegistry()
    private val evaluations = LinkedHashSet<EvaluationWaiter>()
    private var activeTabId: String? = null
    private val screenshotDir = File(context.filesDir, "browser_screenshots").apply { mkdirs() }

    private class NavigationWaiter {
        val done = CountDownLatch(1)
        var started = false
        var generation = -1L
        var currentUrl = ""
        var error: String? = null
    }

    private class EvaluationWaiter {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
    }

    private class TabSession(val tabId: String, val webView: WebView) {
        var generation = 0L
        var pageTitle = ""
        var pageUrl = "about:blank"
        var navigation: NavigationWaiter? = null
    }

    fun browserNavigate(url: String): String = serialized {
        validateUrl(url)
        requireWorkerForAsync("导航")
        val waiter = NavigationWaiter()
        val tabId = onMain { getActiveTab().also { it.navigation = waiter }.tabId }
        onMain { tabs.getValue(tabId).webView.loadUrl(url) }
        try {
            await(waiter.done, NAVIGATION_TIMEOUT_MS, "页面加载")
        } catch (error: TimeoutException) {
            onMain {
                tabs[tabId]?.takeIf { it.navigation === waiter }?.apply {
                    navigation = null
                    webView.stopLoading()
                }
            }
            throw error
        }
        val state = onMain {
            val tab = tabs[tabId] ?: throw IllegalStateException("标签页已关闭")
            if (tab.navigation === waiter) tab.navigation = null
            waiter.error?.let { throw IllegalStateException("导航失败: $it") }
            check(waiter.started && tab.generation == waiter.generation) { "导航已被更新的页面取代" }
            Triple(tab.pageTitle, tab.pageUrl, tab.generation)
        }
        "已打开: ${state.first}\nURL: ${state.second}\n标签页: $tabId\nGeneration: ${state.third}"
    }

    fun browserSnapshot(): String = serialized {
        val tab = onMain { getActiveTab() }
        val generation = onMain { requireCurrent(tab).generation }
        val raw = evaluate(tab, SNAPSHOT_SCRIPT, generation, verifyAfter = true)
        val snapshot = JSONObject(raw)
        val refMap = linkedMapOf<String, String>()
        val jsonRefs = snapshot.getJSONObject("refs")
        jsonRefs.keys().forEach { ref -> refMap[ref] = jsonRefs.getString(ref) }
        onMain {
            requireCurrent(tab)
            refs.replace(tab.tabId, generation, refMap)
        }
        val metadata = onMain { Triple(requireCurrent(tab).pageTitle, tab.pageUrl, tab.tabId) }
        "页面: ${metadata.first} (${metadata.second})\n标签页: ${metadata.third}\nGeneration: $generation\n结构: ${snapshot.get("tree")}"
    }

    fun browserClick(ref: String) = serialized {
        val (tab, element) = resolve(ref)
        val clicked = evaluate(tab, elementScript(element.selector, "el.click();return true"), element.generation)
        check(clicked == "true") { "元素 $ref 不存在" }
    }

    fun browserType(ref: String, text: String) = serialized {
        val (tab, element) = resolve(ref)
        val action = "el.focus();if('value' in el){el.value=${JSONObject.quote(text)}}" +
            "else if(el.isContentEditable){el.textContent=${JSONObject.quote(text)}}else{return false}" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return true"
        check(evaluate(tab, elementScript(element.selector, action), element.generation) == "true") { "元素 $ref 不可输入" }
    }

    fun browserScreenshot(elementRef: String?): String = serialized {
        val tab = onMain { getActiveTab() }
        val rect = elementRef?.let { ref ->
            val element = onMain { refs.resolve(tab.tabId, requireCurrent(tab).generation, ref) }
            val value = evaluate(tab, elementScript(element.selector, "var r=el.getBoundingClientRect();" +
                "return {x:r.left,y:r.top,w:r.width,h:r.height}"), element.generation)
            JSONObject(value)
        }
        val bitmap = onMain { drawBitmap(requireCurrent(tab), rect) }
        saveAndEncode(bitmap)
    }

    fun browserScroll(ref: String?) = serialized {
        val tab = onMain { getActiveTab() }
        val script = if (ref == null) {
            "window.scrollBy({top:innerHeight*0.8,behavior:'instant'});true"
        } else {
            val element = onMain { refs.resolve(tab.tabId, requireCurrent(tab).generation, ref) }
            elementScript(element.selector, "return true")
        }
        evaluate(tab, script, onMain { requireCurrent(tab).generation })
    }

    fun browserEvaluate(expression: String): String = serialized {
        evaluate(onMain { getActiveTab() }, expression)
    }

    fun browserTabs(action: String, tabId: String?): String = serialized {
        onMain {
            when (action) {
                "list" -> buildString {
                    append("标签页(${tabs.size} 个):\n")
                    tabs.forEach { (id, tab) ->
                        append("#$id ${if (id == activeTabId) "[当前]" else ""} ${tab.pageTitle} - ${tab.pageUrl} [g${tab.generation}]\n")
                    }
                }.trimEnd()
                "create" -> {
                    val tab = createTab()
                    activeTabId = tab.tabId
                    "已新建标签页 #${tab.tabId}"
                }
                "close" -> {
                    val id = tabId ?: activeTabId ?: throw IllegalStateException("没有可关闭的标签页")
                    require(tabs.size > 1) { "无法关闭最后一个标签页" }
                    val removed = tabs.remove(id) ?: throw IllegalArgumentException("标签页 #$id 不存在")
                    removed.navigation?.apply { error = "标签页已关闭"; done.countDown() }
                    refs.removeTab(id)
                    removed.webView.stopLoading()
                    removed.webView.destroy()
                    if (activeTabId == id) activeTabId = tabs.keys.first()
                    "已关闭标签页 #$id"
                }
                "switch" -> {
                    val id = requireNotNull(tabId) { "请指定标签页 ID" }
                    require(tabs.containsKey(id)) { "标签页 #$id 不存在" }
                    activeTabId = id
                    "已切换到标签页 #$id"
                }
                else -> throw IllegalArgumentException("未知操作: $action")
            }
        }
    }

    fun browserDialog(action: String): String = serialized {
        val script = when (action) {
            "accept" -> "window.confirm=()=>true;window.alert=()=>{};window.prompt=()=>'';true"
            "dismiss" -> "window.confirm=()=>false;window.alert=()=>{};window.prompt=()=>null;true"
            else -> throw IllegalArgumentException("未知操作: $action")
        }
        evaluate(onMain { getActiveTab() }, script)
        if (action == "accept") "已接受对话框" else "已关闭对话框"
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        onMain(allowDestroyed = true) {
            tabs.values.forEach { tab ->
                tab.navigation?.apply { error = "浏览器已销毁"; done.countDown() }
                tab.webView.stopLoading()
                tab.webView.destroy()
            }
            tabs.clear()
            refs.clear()
            evaluations.forEach {
                it.failure.set(IllegalStateException("浏览器已销毁"))
                it.done.countDown()
            }
            evaluations.clear()
            activeTabId = null
        }
    }

    private fun createTab(): TabSession {
        checkMainThread()
        val id = UUID.randomUUID().toString().take(8)
        val webView = WebView(context.applicationContext)
        val tab = TabSession(id, webView)
        tabs[id] = tab
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.generation++
                tab.pageUrl = url
                refs.removeTab(tab.tabId)
                tab.navigation?.apply {
                    started = true
                    generation = tab.generation
                    currentUrl = url
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.pageTitle = view.title.orEmpty()
                tab.pageUrl = url
                tab.navigation?.takeIf {
                    it.started && it.generation == tab.generation && it.currentUrl == url
                }?.done?.countDown()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failNavigation(tab, error.description.toString())
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                failNavigation(tab, "TLS 错误: ${error.primaryError}")
            }
        }
        return tab
    }

    private fun failNavigation(tab: TabSession, message: String) {
        tab.navigation?.apply { error = message; done.countDown() }
    }

    private fun getActiveTab(): TabSession {
        checkMainThread()
        checkAlive()
        val current = activeTabId?.let(tabs::get)
        if (current != null) return current
        return createTab().also { activeTabId = it.tabId }
    }

    private fun requireCurrent(tab: TabSession): TabSession {
        checkMainThread()
        check(tabs[tab.tabId] === tab) { "标签页已关闭" }
        return tab
    }

    private fun resolve(ref: String): Pair<TabSession, BrowserElementRef> = onMain {
        val tab = getActiveTab()
        tab to refs.resolve(tab.tabId, tab.generation, ref)
    }

    private fun evaluate(
        tab: TabSession,
        script: String,
        expectedGeneration: Long? = null,
        verifyAfter: Boolean = false
    ): String {
        requireWorkerForAsync("JavaScript 执行")
        val waiter = EvaluationWaiter()
        mainHandler.post {
            try {
                val current = requireCurrent(tab)
                check(expectedGeneration == null || current.generation == expectedGeneration) { "页面已更新，请重新获取 snapshot" }
                evaluations.add(waiter)
                current.webView.evaluateJavascript(script) { raw ->
                    if (!evaluations.remove(waiter)) return@evaluateJavascript
                    try {
                        requireCurrent(tab)
                        check(!verifyAfter || expectedGeneration == null || tab.generation == expectedGeneration) {
                            "页面已更新，请重新获取 snapshot"
                        }
                        waiter.result.set(decodeJsResult(raw))
                    } catch (error: Throwable) {
                        waiter.failure.set(error)
                    }
                    waiter.done.countDown()
                }
            } catch (error: Throwable) {
                evaluations.remove(waiter)
                waiter.failure.set(error)
                waiter.done.countDown()
            }
        }
        try {
            await(waiter.done, JS_TIMEOUT_MS, "JavaScript 执行")
        } catch (error: TimeoutException) {
            mainHandler.post { evaluations.remove(waiter) }
            throw error
        }
        waiter.failure.get()?.let { throw it }
        return waiter.result.get() ?: "null"
    }

    private fun drawBitmap(tab: TabSession, rect: JSONObject?): Bitmap {
        checkMainThread()
        if (tab.webView.width <= 0 || tab.webView.height <= 0) {
            tab.webView.measure(
                View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(DEFAULT_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            tab.webView.layout(0, 0, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
        }
        val scale = tab.webView.scale.coerceAtLeast(0.1f)
        val width = (rect?.optDouble("w")?.times(scale) ?: tab.webView.width.toDouble()).toInt().coerceAtLeast(1)
        val height = (rect?.optDouble("h")?.times(scale) ?: tab.webView.height.toDouble()).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (rect != null) {
            canvas.scale(scale, scale)
            canvas.translate(-rect.optDouble("x").toFloat(), -rect.optDouble("y").toFloat())
        }
        tab.webView.draw(canvas)
        return bitmap
    }

    private fun saveAndEncode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "截图编码失败" }
            output.toByteArray()
        }
        val filename = "screenshot_${System.currentTimeMillis()}.png"
        FileOutputStream(File(screenshotDir, filename)).use { it.write(bytes) }
        return "截图已保存: $filename\n数据: ${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun elementScript(selector: String, action: String) =
        "(function(){var el=document.querySelector(${JSONObject.quote(selector)});" +
            "if(!el)return false;el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});$action})()"

    private fun decodeJsResult(raw: String?): String {
        val value = JSONTokener(raw ?: "null").nextValue()
        return if (value is String) value else value.toString()
    }

    private fun validateUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
            "仅支持有效的 http/https 地址"
        }
    }

    private fun requireWorkerForAsync(operation: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "$operation 必须从工作线程调用" }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebView 只能在主线程访问" }

    private fun checkAlive() = check(!destroyed.get()) { "浏览器已销毁" }

    private fun await(latch: CountDownLatch, timeoutMs: Long, operation: String) {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException("${operation}超时 (${timeoutMs}ms)")
    }

    private fun <T> serialized(block: () -> T): T = synchronized(operationLock) {
        checkAlive()
        block()
    }

    private fun <T> onMain(allowDestroyed: Boolean = false, block: () -> T): T {
        if (!allowDestroyed) checkAlive()
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        val cancelled = AtomicBoolean(false)
        mainHandler.post {
            if (!cancelled.get()) result.set(runCatching(block))
            done.countDown()
        }
        try {
            await(done, MAIN_TIMEOUT_MS, "主线程任务")
        } catch (error: TimeoutException) {
            cancelled.set(true)
            throw error
        }
        return result.get().getOrThrow()
    }

    private companion object {
        const val MAIN_TIMEOUT_MS = 10_000L
        const val JS_TIMEOUT_MS = 10_000L
        const val NAVIGATION_TIMEOUT_MS = 20_000L
        const val DEFAULT_VIEWPORT_WIDTH = 1080
        const val DEFAULT_VIEWPORT_HEIGHT = 1920
        val SNAPSHOT_SCRIPT = """
            (function(){
              var refs={}, next=1;
              function walk(n,d){
                if(!n||d>32)return null;
                if(n.nodeType===3){var tx=(n.textContent||'').trim();return tx?{tag:'#text',text:tx.substring(0,200)}:null;}
                if(n.nodeType!==1)return null;
                var out={tag:n.tagName.toLowerCase()}, attrs={};
                ['id','class','type','placeholder','aria-label','role','href','src','alt','name','value'].forEach(function(a){var v=n.getAttribute(a);if(v)attrs[a]=v;});
                if(Object.keys(attrs).length)out.attrs=attrs;
                var interactive=n.matches('a,button,input,textarea,select,[role=button],[role=link],[contenteditable=true],[onclick]');
                if(interactive){var ref='e'+next++;n.setAttribute('data-monkeycode-ref',ref);refs[ref]='[data-monkeycode-ref="'+ref+'"]';out.ref=ref;}
                var r=n.getBoundingClientRect();if(r.width>0||r.height>0)out.rect={x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};
                var children=[];for(var i=0;i<n.childNodes.length;i++){var child=walk(n.childNodes[i],d+1);if(child)children.push(child);}if(children.length)out.children=children;
                return out;
              }
              return JSON.stringify({tree:walk(document.body||document.documentElement,0),refs:refs});
            })()
        """.trimIndent()
    }
}
