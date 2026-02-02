package com.example.mxmgbrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var txtMedia: TextView
    private var lastKey = ""
    private var lastAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtMedia = findViewById(R.id.txtMedia)
        web = findViewById(R.id.web)

        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.contains(".mp4", true) || url.contains(".m3u8", true)) {
                    remember(url)
                }
                return false
            }
        }

        web.loadUrl("https://example.com")
    }

    private fun toMasterHls(u: String): String {
        val low = u.lowercase()
        val i = low.indexOf(".mp4/")
        if (i >= 0) return u.substring(0, i + 4) + ".m3u8"
        if (low.endsWith(".mp4") && !low.contains(".m3u8")) return u + ".m3u8"
        return u
    }

    private fun remember(raw: String) {
        val fixed = toMasterHls(raw)
        runOnUiThread { txtMedia.text = fixed }

        val key = fixed.substringBefore("?").lowercase()
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 15000) return

        lastKey = key
        lastAt = now
        openMx(fixed)
    }

    private fun openMx(url: String) {
        val pkgs = listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")
        val pm = packageManager
        val found = pkgs.firstOrNull {
            try { pm.getPackageInfo(it, 0); true } catch (_: Exception) { false }
        }

        if (found == null) {
            Toast.makeText(this, "ไม่พบ MX Player", Toast.LENGTH_LONG).show()
            return
        }

        val i = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            type = "application/vnd.apple.mpegurl"
            setPackage(found)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
    }
}