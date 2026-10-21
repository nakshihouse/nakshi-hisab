package com.nakshihouse.hisab

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast

/**
 * নকশি হাউস হিসাব — native Android wrapper around the Apps Script web app.
 *
 * - Fullscreen WebView loads the web app URL. Default comes from
 *   R.string.web_app_url, but the user can change it inside the app
 *   (Settings -> server URL) — the choice is kept in SharedPreferences,
 *   so one APK can serve many deployments without a rebuild.
 * - Zoom is disabled and overview mode is off: the page is a fixed,
 *   phone-width app UI — no pinch zoom, no sideways pan.
 * - JavascriptInterface "HisabApp" bridges voice input: Android WebView has
 *   NO Web Speech API, so the page calls HisabApp.startVoice() and the
 *   native side returns the transcript via onNativeVoiceResult(text).
 * - onShowFileChooser wires the web page's receipt-photo <input type=file>
 *   to the system file picker (without this the photo button silently dies).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val REQ_FILE_CHOOSER = 1001
        private const val REQ_VOICE = 1002
    }

    // Shown if the web app URL was never pasted into strings.xml.
    private val setupHtml = """
        <!DOCTYPE html><html lang="bn"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{font-family:sans-serif;background:#FAF7F2;color:#1C1C1C;
        display:flex;align-items:center;justify-content:center;min-height:90vh;margin:0;text-align:center}
        .card{background:#fff;border:1px solid #E5DDD2;border-radius:14px;padding:28px;max-width:330px}
        code{background:#EFE9DC;padding:2px 6px;border-radius:6px}</style></head>
        <body><div class="card"><h2>সেটআপ বাকি</h2>
        <p>APK বিল্ডের আগে <code>res/values/strings.xml</code>-এ
        <code>web_app_url</code>-তে Apps Script Web app URL বসান।</p></div></body></html>
    """.trimIndent()

    // Shown when the main page fails to load (usually: no internet).
    private val offlineHtml = """
        <!DOCTYPE html><html lang="bn"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{font-family:sans-serif;background:#FAF7F2;color:#1C1C1C;
        display:flex;align-items:center;justify-content:center;min-height:90vh;margin:0;text-align:center}
        .card{background:#fff;border:1px solid #E5DDD2;border-radius:14px;padding:28px;max-width:330px}
        button{background:#2F6B45;color:#fff;border:0;border-radius:12px;
        padding:14px 22px;font-size:18px;margin-top:12px}</style></head>
        <body><div class="card"><h2>ইন্টারনেট নেই</h2>
        <p>নেট সংযোগ চালু করে আবার চেষ্টা করুন।</p>
        <button onclick="location.reload()">আবার চেষ্টা করুন</button>
        <button onclick="HisabApp.openServerSettings()">সার্ভার ঠিকানা ঠিক করুন</button></div></body></html>
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Keep every navigation inside the app's WebView.
                view.loadUrl(request.url.toString())
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(null, offlineHtml, "text/html", "utf-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Drop any pending chooser so only one is ever active.
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams.createIntent()
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(
                        this@MainActivity,
                        "ফাইল বাছাই করা যাচ্ছে না",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }
        }

        webView.addJavascriptInterface(HisabBridge(), "HisabApp")

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val url = serverUrl()
            if (url.startsWith("http")) {
                webView.loadUrl(url)
            } else {
                webView.loadDataWithBaseURL(null, setupHtml, "text/html", "utf-8", null)
            }
        }
    }

    private fun prefs() = getSharedPreferences("hisab", MODE_PRIVATE)

    /** The URL the app should talk to: the user's saved choice, else the built-in default. */
    private fun serverUrl(): String =
        prefs().getString("server_url", null)?.takeIf { it.startsWith("http") }
            ?: getString(R.string.web_app_url)

    /** Dialog to view/change the server URL without rebuilding the APK. */
    private fun showServerUrlDialog() {
        val input = EditText(this).apply {
            setText(serverUrl())
            setSelection(text.length)
            hint = "https://script.google.com/macros/s/.../exec"
        }
        AlertDialog.Builder(this)
            .setTitle("সার্ভার ঠিকানা (URL)")
            .setMessage("অন্য হিসাব-সিস্টেমে যুক্ত করতে নতুন ঠিকানা বসান। সেভ করলেই অ্যাপ সেই ঠিকানায় চলবে।")
            .setView(input)
            .setPositiveButton("সেভ করুন") { _, _ ->
                val u = input.text.toString().trim()
                if (u.startsWith("http")) {
                    prefs().edit().putString("server_url", u).apply()
                    webView.loadUrl(u)
                    Toast.makeText(this, "নতুন ঠিকানায় যুক্ত হচ্ছে...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "ঠিকানাটা ঠিক নেই", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("আগের ঠিকানা") { _, _ ->
                prefs().edit().remove("server_url").apply()
                webView.loadUrl(serverUrl())
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    /**
     * JS bridge exposed to the web page as window.HisabApp.
     * Web Speech API does not exist in Android WebView, so voice input
     * goes through the native speech recognizer (bn-BD).
     */
    inner class HisabBridge {
        @JavascriptInterface
        fun getServerUrl(): String = serverUrl()

        @JavascriptInterface
        fun openServerSettings() {
            runOnUiThread { showServerUrlDialog() }
        }

        @JavascriptInterface
        fun startVoice() {
            // JavascriptInterface methods run on a background thread.
            runOnUiThread {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        getString(R.string.voice_prompt)
                    )
                }
                try {
                    startActivityForResult(intent, REQ_VOICE)
                } catch (e: ActivityNotFoundException) {
                    webView.evaluateJavascript("onNativeVoiceError()", null)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // startActivityForResult is the zero-dependency path (no AndroidX).
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE_CHOOSER -> {
                val cb = filePathCallback
                filePathCallback = null
                if (resultCode == RESULT_OK) {
                    val uris = mutableListOf<Uri>()
                    data?.let { intent ->
                        val clip = intent.clipData
                        if (clip != null) {
                            for (i in 0 until clip.itemCount) {
                                uris.add(clip.getItemAt(i).uri)
                            }
                        } else {
                            intent.data?.let { uris.add(it) }
                        }
                    }
                    cb?.onReceiveValue(uris.toTypedArray())
                } else {
                    cb?.onReceiveValue(null)
                }
            }
            REQ_VOICE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val text = data
                        .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                    // Escape for safe injection into a single-quoted JS string.
                    val escaped = text
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "")
                    webView.evaluateJavascript("onNativeVoiceResult('$escaped')", null)
                } else {
                    webView.evaluateJavascript("onNativeVoiceError()", null)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // framework onBackPressed still works on targetSdk 34.
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
