package com.digiforge.omnireader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var ready = false
    private var pendingJs: String? = null
    @Volatile private var sharedUri: Uri? = null
    private var chooser: ValueCallback<Array<Uri>>? = null

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); chooser = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/shared/", WebViewAssetLoader.PathHandler {
                val u = sharedUri ?: return@PathHandler null
                try { WebResourceResponse(contentResolver.getType(u) ?: "application/octet-stream", null, contentResolver.openInputStream(u)) } catch (e: Exception) { null }
            }).build()
        web = WebView(this).apply {
            setBackgroundColor(0xFF0B1218.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = loader.shouldInterceptRequest(r.url)
                override fun onPageFinished(v: WebView, url: String) { ready = true; pendingJs?.let { v.evaluateJavascript(it, null); pendingJs = null } }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null); chooser = cb
                    return try { pick.launch(p.createIntent()); true } catch (e: Exception) { chooser = null; false }
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun handle(i: Intent?) {
        i ?: return
        @Suppress("DEPRECATION")
        val uri: Uri = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        sharedUri = uri
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        try { contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ix >= 0 && c.moveToFirst()) name = c.getString(ix) } } catch (e: Exception) {}
        val mime = i.type ?: contentResolver.getType(uri) ?: ""
        val js = "openShared(${JSONObject.quote(name)},${JSONObject.quote(mime)})"
        if (ready) web.evaluateJavascript(js, null) else pendingJs = js
    }
}
