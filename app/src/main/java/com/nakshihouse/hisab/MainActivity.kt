package com.nakshihouse.hisab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * নকশি হাউস হিসাব — native Android wrapper around the Apps Script web app.
 *
 * - Fullscreen WebView loads the web app URL. Default comes from
 *   R.string.web_app_url, but the user can change it inside the app
 *   (Settings -> server URL) — the choice is kept in SharedPreferences,
 *   so one APK can serve many deployments without a rebuild.
 * - Zoom is disabled and overview mode is off: the page is a fixed,
 *   phone-width app UI — no pinch zoom, no sideways pan.
 * - Self-update: on launch the app checks this repo's GitHub Releases for a
 *   newer versionCode; if one exists it offers a one-tap download + install
 *   (no Play Store). Release tags look like "v<versionCode>-<versionName>".
 * - JavascriptInterface "HisabApp" bridges voice input: Android WebView has
 *   NO Web Speech API, so the page calls HisabApp.startVoice() and the
 *   native side returns the transcript via onNativeVoiceResult(text).
 * - onShowFileChooser wires the web page's receipt-photo <input type=file>
 *   to the system file picker (without this the photo button silently dies).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ---- native voice recorder state (segments pushed to the page) ----
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var pendingRecordStart = false
    private var segmentIndex = 0
    private val recHandler = Handler(Looper.getMainLooper())
    private var segmentFile: File? = null

    companion object {
        private const val REQ_FILE_CHOOSER = 1001
        private const val REQ_VOICE = 1002
        private const val REQ_MIC = 1003
        private const val SEGMENT_MS = 12_000L
        private const val UPDATE_API_URL =
            "https://api.github.com/repos/nakshihouse/nakshi-hisab/releases/latest"
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
            // Narrow viewport: the host page (Apps Script) declares no
            // viewport of its own; a wide default would leave room to pan.
            useWideViewPort = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }

        // Pin the horizontal axis: this is a fixed phone-width UI, so any
        // sideways pan of the host page is a bug — force scrollX back to 0.
        webView.isHorizontalScrollBarEnabled = false
        webView.setOnScrollChangeListener { v, sx, sy, _, _ ->
            if (sx != 0) v.scrollTo(0, sy)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // Pin the HOST document too (Apps Script wraps the app in
                // its own page) so it cannot pan sideways around the app.
                view.evaluateJavascript(
                    "(function(){try{var d=document;" +
                        "d.documentElement.style.setProperty('overflow-x','hidden','important');" +
                        "if(d.body){d.body.style.setProperty('overflow-x','hidden','important');}" +
                        "}catch(e){}})();",
                    null
                )
            }

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
        checkForUpdate()
    }

    // ============================ SELF-UPDATE ============================
    // Source of truth: GitHub Releases of the app-shell repo. The release
    // workflow publishes tag "v<versionCode>-<versionName>" plus the signed
    // APK as an asset named nakshi-hisab.apk.

    private fun installedVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = URL(UPDATE_API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "NakshiHisabApp")
                if (conn.responseCode != 200) return@Thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val rel = JSONObject(body)
                val tag = rel.optString("tag_name") // e.g. v2-1.1
                val remoteVc = tag.removePrefix("v").substringBefore("-").toLongOrNull()
                    ?: return@Thread
                if (remoteVc <= installedVersionCode()) return@Thread
                val remoteVn = tag.substringAfter("-", "")
                val assets = rel.optJSONArray("assets") ?: return@Thread
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                val url = apkUrl ?: return@Thread
                runOnUiThread { promptUpdate(remoteVn, url) }
            } catch (e: Exception) {
                // Offline or GitHub unreachable — updates are best-effort.
            }
        }.start()
    }

    private fun promptUpdate(versionName: String, apkUrl: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("নতুন আপডেট এসেছে")
            .setMessage("হিসাব অ্যাপের নতুন সংস্করণ (v$versionName) তৈরি। এখন আপডেট করবেন?")
            .setPositiveButton("আপডেট করুন") { _, _ -> downloadUpdate(apkUrl) }
            .setNegativeButton("পরে", null)
            .show()
    }

    @Suppress("DEPRECATION") // ProgressDialog: zero-dependency progress UI.
    private fun downloadUpdate(apkUrl: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "আপডেট বসাতে এই অ্যাপকে অনুমতি দিন",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        val progress = ProgressDialog(this).apply {
            setMessage("আপডেট ডাউনলোড হচ্ছে...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val outFile = File(dir, "nakshi-hisab-update.apk")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "NakshiHisabApp")
                conn.instanceFollowRedirects = true
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                runOnUiThread {
                                    progress.isIndeterminate = false
                                    progress.max = 100
                                    progress.progress = pct
                                }
                            }
                        }
                    }
                }
                runOnUiThread {
                    progress.dismiss()
                    installUpdate(outFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(
                        this,
                        "ডাউনলোড হয়নি — নেট দেখে আবার চেষ্টা করুন",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun installUpdate(apkFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        startActivity(intent)
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

        // In-app voice recording: the shell records natively (the Apps
        // Script iframe cannot use the microphone) and pushes ~12s audio
        // segments to the page, which sends them to Gemini for live text.
        @JavascriptInterface
        fun startRecorder() {
            runOnUiThread { bridgeStartRecorder() }
        }

        @JavascriptInterface
        fun stopRecorder() {
            runOnUiThread { bridgeStopRecorder() }
        }

        @JavascriptInterface
        fun cancelRecorder() {
            runOnUiThread { bridgeCancelRecorder() }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    // ==================== native voice recorder ====================
    private fun js(script: String) {
        runOnUiThread {
            if (::webView.isInitialized && !isFinishing) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun voiceDir(): File {
        val dir = File(cacheDir, "voice")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun bridgeStartRecorder() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecordStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        beginRecording()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && pendingRecordStart) {
            pendingRecordStart = false
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                beginRecording()
            } else {
                js("onRecorderError('permission')")
            }
        }
    }

    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            finishSegment(push = true)
            startSegment()
            if (recording) recHandler.postDelayed(this, SEGMENT_MS)
        }
    }

    private val levelRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            val amp = try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }
            val level = Math.pow(amp / 32767.0, 0.6).coerceIn(0.0, 1.0)
            js("onVoiceLevel(" + String.format(java.util.Locale.US, "%.3f", level) + ")")
            recHandler.postDelayed(this, 250)
        }
    }

    private fun beginRecording() {
        voiceDir().listFiles()?.forEach { it.delete() }
        segmentIndex = 0
        recording = true
        startSegment()
        if (!recording) return // startSegment already reported the error
        js("onRecorderStarted()")
        recHandler.postDelayed(rotateRunnable, SEGMENT_MS)
        recHandler.post(levelRunnable)
    }

    private fun startSegment() {
        segmentFile = File(voiceDir(), "seg_" + segmentIndex + ".m4a")
        segmentIndex++
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(64000)
            rec.setOutputFile(segmentFile!!.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
        } catch (e: Exception) {
            try {
                rec.release()
            } catch (ignored: Exception) {
            }
            recorder = null
            recording = false
            js("onRecorderError('failed')")
        }
    }

    /** Stops the current segment; pushes it to the page when asked. */
    private fun finishSegment(push: Boolean) {
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (ignored: Exception) {
            // Too short / no valid data — the segment is dropped below.
        }
        try {
            rec.release()
        } catch (ignored: Exception) {
        }
        val f = segmentFile
        segmentFile = null
        if (f != null && f.exists()) {
            if (push && f.length() > 1500) {
                try {
                    val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    js("onVoiceSegment('" + b64 + "','audio/mp4')")
                } catch (ignored: Exception) {
                }
            }
            f.delete()
        }
    }

    private fun bridgeStopRecorder() {
        if (!recording) {
            js("onRecorderStopped()")
            return
        }
        recording = false
        recHandler.removeCallbacks(rotateRunnable)
        recHandler.removeCallbacks(levelRunnable)
        finishSegment(push = true)
        js("onRecorderStopped()")
    }

    private fun bridgeCancelRecorder() {
        recording = false
        recHandler.removeCallbacks(rotateRunnable)
        recHandler.removeCallbacks(levelRunnable)
        finishSegment(push = false)
        voiceDir().listFiles()?.forEach { it.delete() }
        js("onRecorderCancelled()")
    }

    // ==================== back button ====================
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Let the page handle it first: close an open sheet/settings, go
        // back to the chat tab, or cancel a recording. Exit only when the
        // page says it has nothing to handle.
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.hisabHandleBack ? String(hisabHandleBack()) : 'false'"
            ) { result ->
                if (result != "\"true\"") exitApp()
            }
        } else {
            exitApp()
        }
    }

    // 'super' cannot be called from inside a lambda, hence this helper.
    private fun exitApp() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (recording) {
            recording = false
            recHandler.removeCallbacks(rotateRunnable)
            recHandler.removeCallbacks(levelRunnable)
            try {
                recorder?.stop()
            } catch (ignored: Exception) {
            }
            try {
                recorder?.release()
            } catch (ignored: Exception) {
            }
            recorder = null
        }
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
