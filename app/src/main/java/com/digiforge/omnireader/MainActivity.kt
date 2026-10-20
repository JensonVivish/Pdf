package com.digiforge.omnireader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var ready = false
    private val pending = ArrayList<String>()
    private var chooser: ValueCallback<Array<Uri>>? = null
    private val io = Executors.newSingleThreadExecutor()
    private val sharedDir: File by lazy { File(cacheDir, "shared").apply { mkdirs() } }

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooser = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/shared/", WebViewAssetLoader.InternalStoragePathHandler(this, sharedDir))
            .build()
        web = WebView(this).apply {
            setBackgroundColor(0xFF0B1218.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    ready = true
                    pending.forEach { view.evaluateJavascript(it, null) }
                    pending.clear()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = cb
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    return try { pick.launch(i); true } catch (e: Exception) { chooser = null; false }
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun runJs(js: String) {
        val wrapped = "(function f(){if(typeof openShared==='function'){" + js + "}else setTimeout(f,200)})()"
        if (ready) web.evaluateJavascript(wrapped, null) else pending.add(wrapped)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    // Copy the shared file into app storage right away (the sender's read permission can expire),
    // then tell the page to load it.
    private fun handle(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> streamExtra(i)
                ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        if (uri == null) return
        val mime = i.type ?: contentResolver.getType(uri) ?: ""
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) { }
        val shownName = name
        io.execute {
            try {
                sharedDir.listFiles()?.forEach { it.delete() }
                val out = File(sharedDir, "f" + System.currentTimeMillis())
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("could not open file")
                input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
                val js = "openShared(" + JSONObject.quote(shownName) + "," + JSONObject.quote(mime) + "," + JSONObject.quote(out.name) + ")"
                runOnUiThread { runJs(js) }
            } catch (e: Exception) {
                val js = "toast(" + JSONObject.quote("Couldn't read the shared file: " + e.message) + ")"
                runOnUiThread { runJs(js) }
            }
        }
    }
}
