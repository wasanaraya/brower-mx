package com.example.mxmgbrowser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var etUrl: EditText

    private val homeUrl = "https://www.google.com"
    private var lastMediaUrl: String = ""

    private val AUTO_FORCE_HIGHEST = true
    private val AUTO_OPEN_ON_DETECT = true

    private var lastAutoOpened: String = ""
    private var lastAutoOpenedAtMs: Long = 0

    data class HlsVariant(
        val resolution: String,
        val bandwidth: Long,
        val url: String
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // แสดงสาเหตุ crash แทนเด้งเงียบ (ช่วย debug บน Android 15/TV)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Crash: ${e.javaClass.simpleName}\n${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        etUrl = findViewById(R.id.etUrl)

        // WebView provider อาจหาย/พังในบางเครื่อง (โดยเฉพาะ TV) -> กันไว้
        try {
            web = findViewById(R.id.web)
        } catch (t: Throwable) {
            Toast.makeText(this, "WebView not available: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Soul-like bar buttons
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (web.canGoBack()) web.goBack() else toast("ย้อนกลับไม่ได้")
        }
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (web.canGoForward()) web.goForward() else toast("ไปข้างหน้าไม่ได้")
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener { web.reload() }
        findViewById<Button>(R.id.btnHomeBar).setOnClickListener { web.loadUrl(homeUrl) }

        findViewById<Button>(R.id.btnGo).setOnClickListener {
            val text = etUrl.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val url = if (text.startsWith("http://") || text.startsWith("https://")) {
                text
            } else {
                "https://www.google.com/search?q=" + Uri.encode(text)
            }
            web.loadUrl(url)
        }

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val clip = readClipboardText()
            if (clip.isBlank()) {
                toast("คลิปบอร์ดว่าง")
                return@setOnClickListener
            }

            if (clip.contains(".mp4", true) || clip.contains(".m3u8", true) || clip.contains(".mpd", true)) {
                val fixed = fixForMx(clip)
                etUrl.setText(fixed)
                remember(fixed, "paste")
                if (fixed.startsWith("http")) web.loadUrl(fixed)
            } else {
                val url = if (clip.startsWith("http://") || clip.startsWith("https://")) {
                    clip
                } else {
                    "https://www.google.com/search?q=" + Uri.encode(clip)
                }
                etUrl.setText(url)
                web.loadUrl(url)
            }
        }

        findViewById<Button>(R.id.btnQuality).setOnClickListener {
            if (lastMediaUrl.isBlank()) toast("ยังไม่พบลิงก์วิดีโอ") else showQualityDialog(lastMediaUrl)
        }

        findViewById<Button>(R.id.btnOpenMx).setOnClickListener {
            if (lastMediaUrl.isBlank()) toast("ยังไม่พบลิงก์วิดีโอ") else openMx(lastMediaUrl)
        }

        etUrl.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                findViewById<Button>(R.id.btnGo).performClick()
                true
            } else false
        }

        // Web settings
        val ws = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.loadsImagesAutomatically = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.userAgentString = ws.userAgentString + " MXB/1.0"

        // Capture URLs from injected JS via console
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = consoleMessage.message() ?: ""
                if (msg.startsWith("MXB_URL:")) {
                    val url = msg.removePrefix("MXB_URL:").trim()
                    remember(url, "js")
                    return true
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                setUrlBar(url)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setUrlBar(url)
                injectHooks()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isMediaUrl(url)) remember(url, "net")
                return null
            }
        }

        web.loadUrl(homeUrl)
    }

    private fun setUrlBar(url: String?) {
        val u = (url ?: "").trim()
        if (u.isNotBlank()) etUrl.setText(u)
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun readClipboardText(): String {
        return try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = cb.primaryClip?.getItemAt(0)
            (item?.coerceToText(this)?.toString() ?: "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isMediaUrl(u: String): Boolean {
        val url = u.lowercase()
        return url.contains(".m3u8") ||
                url.contains(".mp4") ||
                url.contains(".mpd") ||
                url.contains(".ts")
    }

    // เคสของคุณ: mp4 -> เติม .m3u8 ต่อท้าย (ถ้ายังไม่มี)
    private fun fixForMx(u: String): String {
        val url = u.trim()
        val low = url.lowercase()
        if (low.contains(".m3u8")) return url
        return if (low.endsWith(".mp4")) url + ".m3u8" else url
    }

    private fun remember(url: String, why: String) {
        val fixed = fixForMx(url)
        if (fixed.length < 10) return

        lastMediaUrl = fixed

        // Auto force quality highest สำหรับ m3u8
        if (AUTO_FORCE_HIGHEST && AUTO_OPEN_ON_DETECT && fixed.lowercase().contains(".m3u8")) {
            autoForceHighestAndOpen(fixed)
        }
    }

    private fun openMx(url: String) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            type = "application/vnd.apple.mpegurl"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(i, "Open with"))
    }

    private fun injectHooks() {
        val js =
            "(function(){if(window.__mxb_hooked)return;window.__mxb_hooked=true;" +
            "function emit(u){try{if(u)console.log('MXB_URL:'+u);}catch(e){}}" +
            "const _fetch=window.fetch;if(_fetch){window.fetch=function(){try{emit(arguments[0]);}catch(e){}" +
            "return _fetch.apply(this,arguments);}}" +
            "const _open=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(method,url){" +
            "try{emit(url);}catch(e){}return _open.apply(this,arguments);};" +
            "function scan(){document.querySelectorAll('video,source').forEach(function(el){" +
            "emit(el.src||el.getAttribute('src'));});}scan();setInterval(scan,2000);" +
            "})();"

        web.evaluateJavascript(js, null)
    }

    private fun parseHlsVariants(masterUrl: String, callback: (List<HlsVariant>) -> Unit) {
        Thread {
            try {
                val text = URL(masterUrl).readText()
                val lines = text.split("\n")
                val list = mutableListOf<HlsVariant>()
                val infoPattern = Pattern.compile("RESOLUTION=(\\d+x\\d+).*BANDWIDTH=(\\d+)")

                var lastInfo: Pair<String, Long>? = null

                for (raw in lines) {
                    val line = raw.trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val m = infoPattern.matcher(line)
                        if (m.find()) lastInfo = m.group(1) to m.group(2).toLong()
                    } else if (!line.startsWith("#") && line.isNotBlank() && lastInfo != null) {
                        val absUrl =
                            if (line.startsWith("http")) line
                            else masterUrl.substringBeforeLast("/") + "/" + line

                        list.add(HlsVariant(lastInfo!!.first, lastInfo!!.second, absUrl))
                        lastInfo = null
                    }
                }

                runOnUiThread { callback(list) }
            } catch (_: Exception) {
                runOnUiThread { callback(emptyList()) }
            }
        }.start()
    }

    private fun showQualityDialog(masterUrl: String) {
        parseHlsVariants(masterUrl) { list ->
            if (list.isEmpty()) {
                toast("คลิปนี้มีคุณภาพเดียวหรืออ่าน playlist ไม่ได้")
                return@parseHlsVariants
            }

            val sorted = list.sortedByDescending { it.bandwidth }
            val items: Array<CharSequence> =
                sorted.map { "${it.resolution} (${it.bandwidth / 1000} kbps)" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("เลือกคุณภาพ")
                .setItems(items) { _, which ->
                    val selected = sorted[which]
                    remember(selected.url, "pick")
                    openMx(selected.url)
                }
                .setNegativeButton("ยกเลิก", null)
                .show()
        }
    }

    private fun autoForceHighestAndOpen(masterUrl: String) {
        val now = System.currentTimeMillis()
        if (masterUrl == lastAutoOpened && (now - lastAutoOpenedAtMs) < 3000) return
        lastAutoOpened = masterUrl
        lastAutoOpenedAtMs = now

        parseHlsVariants(masterUrl) { list ->
            if (!AUTO_OPEN_ON_DETECT) return@parseHlsVariants

            if (list.isEmpty()) {
                openMx(masterUrl)
                return@parseHlsVariants
            }

            val best = list.maxByOrNull { it.bandwidth }
            if (best != null) openMx(best.url) else openMx(masterUrl)
        }
    }
}
