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
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
data class HlsVariant(
    val resolution: String,
    val bandwidth: Long,
    val url: String
)

// Auto-force highest quality from master playlist and auto-open MX when media is detected
private val AUTO_FORCE_HIGHEST = true
private val AUTO_OPEN_ON_DETECT = true

// prevent repeated auto-open loops
private var lastAutoOpened: String = ""
private var lastAutoOpenedAtMs: Long = 0



    private lateinit var web: WebView
    private lateinit var etUrl: EditText
    private var lastMediaUrl: String = ""

    private val homeUrl = "https://www.google.com"
    private val preferMxType = "application/vnd.apple.mpegurl"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        web = findViewById(R.id.web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.userAgentString = web.settings.userAgentString + " MXBrowser/1.0"

        // JS -> Android bridge
        web.addJavascriptInterface(Bridge(), "MXB")

        web.webChromeClient = WebChromeClient()

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val media = detectMedia(url)
                if (media.isNotEmpty()) {
                    remember(media, "nav")
                    openMx(media)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectHooks()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                try {
                    val u = request?.url?.toString() ?: return null
                    val media = detectMedia(u)
                    if (media.isNotEmpty()) remember(media, "res")
                } catch (_: Exception) {}
                return null
            }
        }

        findViewById<Button>(R.id.btnHomeBar).setOnClickListener { web.loadUrl(homeUrl) }

findViewById<Button>(R.id.btnBack).setOnClickListener {
    if (web.canGoBack()) web.goBack() else toast("ย้อนกลับไม่ได้")
}
findViewById<Button>(R.id.btnForward).setOnClickListener {
    if (web.canGoForward()) web.goForward() else toast("ไปข้างหน้าไม่ได้")
}
findViewById<Button>(R.id.btnReload).setOnClickListener {
    web.reload()
}


// TV-friendly URL bar actions
findViewById<Button>(R.id.btnGo).setOnClickListener {
    val text = etUrl.text?.toString()?.trim().orEmpty()
    if (text.isBlank()) return@setOnClickListener
    val url = if (text.startsWith("http://") || text.startsWith("https://")) text
    else "https://www.google.com/search?q=" + Uri.encode(text)
    web.loadUrl(url)
}

findViewById<Button>(R.id.btnPaste).setOnClickListener {
    val clip = readClipboardText()
    if (clip.isBlank()) { toast("คลิปบอร์ดว่าง"); return@setOnClickListener }

    if (clip.contains(".mp4") || clip.contains(".m3u8")) {
        remember(clip, "paste")
        setUrlBar(clip)
        if (clip.startsWith("http")) web.loadUrl(clip)
        return@setOnClickListener
    }

    val url = if (clip.startsWith("http://") || clip.startsWith("https://")) clip
    else "https://www.google.com/search?q=" + Uri.encode(clip)
    setUrlBar(url)
    web.loadUrl(url)
}

etUrl.setOnEditorActionListener { _, actionId, event ->
    val isGo = actionId == EditorInfo.IME_ACTION_GO ||
            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
    if (isGo) {
        findViewById<Button>(R.id.btnGo).performClick()
        true
    } else false
}

findViewById<Button>(R.id.btnQuality).setOnClickListener {
    if (lastMediaUrl.isBlank()) {
        toast("ยังไม่พบลิงก์วิดีโอ")
    } else {
        showQualityDialog(lastMediaUrl)
    }
}

findViewById<Button>(R.id.btnOpenMx).setOnClickListener {

            if (lastMediaUrl.isBlank()) toast("ยังไม่พบลิงก์วิดีโอ")
            else openMx(lastMediaUrl)
        }

        val incoming = extractIncoming(intent)
        web.loadUrl(incoming ?: homeUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        val incoming = extractIncoming(intent)
        if (!incoming.isNullOrBlank()) web.loadUrl(incoming)
    }

    private fun extractIncoming(i: Intent): String? {
        return when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }

    // requirement: แค่เติม .m3u8 ต่อท้าย .mp4
    private fun fixForMx(url: String): String {
        val u = url.trim().replace(Regex("""[)\]\.,。]+$"""), "")
        return when {
            u.contains(".m3u8", true) -> u
            u.contains(".mp4", true) -> u + ".m3u8"
            else -> u
        }
    }

    private fun detectMedia(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> url
            u.contains(".mp4") -> fixForMx(url)
            u.contains(".ts.m3u8") -> url
            else -> ""
        }
    }

    private fun remember(url: String, why: String) {
    val fixed = fixForMx(url)
    if (fixed.length > 10 && fixed != lastMediaUrl) {
        lastMediaUrl = fixed
        android.util.Log.d("MXB", "FOUND($why): $lastMediaUrl")

        if (AUTO_FORCE_HIGHEST && AUTO_OPEN_ON_DETECT && lastMediaUrl.contains(".m3u8", true)) {
            autoForceHighestAndOpen(lastMediaUrl)
        }
    }
}
    }

    private fun openMx(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.data = Uri.parse(url)
            i.type = preferMxType
            // ไม่ล็อก package -> ให้ระบบเลือก MX เอง (กันปัญหาแพ็กเกจไม่ตรง)
            val chooser = Intent.createChooser(i, "เลือกแอปเล่นวิดีโอ (แนะนำ MX)")
            startActivity(chooser)
        } catch (_: Exception) {
            toast("เปิดแอปเล่นวิดีโอไม่ได้")
        }
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

    private fun copy(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("mx_url", text))
    }

private fun setUrlBar(url: String?) {
    val u = (url ?: "").trim()
    if (u.isNotBlank()) etUrl.setText(u)
}

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

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
                    if (m.find()) {
                        lastInfo = m.group(1) to m.group(2).toLong()
                    }
                } else if (!line.startsWith("#") && line.isNotBlank() && lastInfo != null) {
                    val absUrl =
                        if (line.startsWith("http")) line
                        else masterUrl.substringBeforeLast("/") + "/" + line

                    list.add(
                        HlsVariant(
                            resolution = lastInfo.first,
                            bandwidth = lastInfo.second,
                            url = absUrl
                        )
                    )
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
            toast("คลิปนี้มีคุณภาพเดียว")
            return@parseHlsVariants
        }

        val sorted = list.sortedByDescending { it.bandwidth }
        val items = sorted.map { "${it.resolution}  (${it.bandwidth / 1000} kbps)" }.toTypedArray()

        android.app.AlertDialog.Builder(this)
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
        if (best != null) {
            remember(best.url, "best")
            openMx(best.url)
        } else {
            openMx(masterUrl)
        }
    }
}

private fun injectHooks() {

        val js = """
            (function(){
              if (window.__mx_hooked) return;
              window.__mx_hooked = true;

              function send(u){
                try { if(u) MXB.onFound(String(u)); } catch(e){}
              }

              // hook fetch
              const _fetch = window.fetch;
              if (_fetch) {
                window.fetch = function(){
                  try {
                    const a = arguments[0];
                    const url = (typeof a === 'string') ? a : (a && a.url);
                    if (url && (String(url).includes('.mp4') || String(url).includes('.m3u8'))) send(url);
                  } catch(e){}
                  return _fetch.apply(this, arguments);
                }
              }

              // hook XHR
              const _open = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url){
                try {
                  if (url && (String(url).includes('.mp4') || String(url).includes('.m3u8'))) send(url);
                } catch(e){}
                return _open.apply(this, arguments);
              };

              function scan(){
                try {
                  document.querySelectorAll('video,source').forEach(el=>{
                    const u = el.currentSrc || el.src || el.getAttribute('src');
                    if (u && (String(u).includes('.mp4') || String(u).includes('.m3u8'))) send(u);
                  });
                } catch(e){}
              }
              setInterval(scan, 1200);
              scan();
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onFound(url: String?) {
            if (url.isNullOrBlank()) return
            val u = url.trim()
            if (u.contains(".mp4") || u.contains(".m3u8")) {
                runOnUiThread { remember(u, "js") }
            }
        }
    }
}
