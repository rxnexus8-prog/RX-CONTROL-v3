package com.r4x.rxcontrolzone

import android.content.Intent
import android.os.*
import android.os.VibrationEffect
import android.provider.Settings
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bridgeClient: BridgeClient? = null

    // ── Shizuku permission request code ──────────────────────────────────────
    private val SHIZUKU_REQ = 1001

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWebView()
        setupShizuku()
        startBridgeService()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        bridgeClient?.disconnect()
    }

    override fun onResume() {
        super.onResume()
        // Refresh accessibility status when user returns from settings
        pushStatus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView
    // ─────────────────────────────────────────────────────────────────────────
    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.setBackgroundColor(0xFF07070F.toInt())

        // Inject JavaScript interface as "Android"
        webView.addJavascriptInterface(JsBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Small delay so JS initialises before we push status
                Handler(Looper.getMainLooper()).postDelayed({ pushStatus() }, 400)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Push update to HTML via rxUpdate()
    // ─────────────────────────────────────────────────────────────────────────
    fun pushToWeb(json: JSONObject) {
        val escaped = json.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        runOnUiThread {
            webView.evaluateJavascript("window.rxUpdate('$escaped')", null)
        }
    }

    fun pushLog(msg: String, type: String = "info") {
        pushToWeb(JSONObject().apply { put("log", msg); put("logType", type) })
    }

    fun pushStatus() {
        val accEnabled = isAccessibilityEnabled()
        val shizukuOk  = isShizukuAvailable()
        val bridgeOk   = bridgeClient?.connected == true

        pushToWeb(JSONObject().apply {
            put("status", JSONObject().apply {
                put("accessibility", accEnabled)
                put("shizuku", shizukuOk)
                put("adb", bridgeOk)
                put("python", bridgeOk)
            })
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessibility check
    // ─────────────────────────────────────────────────────────────────────────
    private fun isAccessibilityEnabled(): Boolean {
        val serviceId = "${packageName}/${RxAccessibilityService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains(serviceId, ignoreCase = true)
        } catch (e: Exception) { false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shizuku
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupShizuku() {
        Shizuku.addBinderReceivedListenerSticky {
            pushLog("Shizuku binder received", "ok")
            pushStatus()
        }
        Shizuku.addBinderDeadListener {
            pushLog("Shizuku binder lost", "warn")
            pushStatus()
        }
    }

    private fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                requestPermissions(arrayOf("moe.shizuku.manager.permission.API_V23"), SHIZUKU_REQ)
            } else {
                Shizuku.requestPermission(SHIZUKU_REQ)
            }
        } catch (e: Exception) {
            pushLog("Shizuku not installed — install from Play Store", "warn")
        }
    }

    // Run shell command via Shizuku (returns output string)
    // Note: Shizuku.newProcess is a hidden/private method in the public API jar,
    // so it must be invoked via reflection at runtime.
    fun runShizukuCommand(cmd: String): String {
        return try {
            if (!isShizukuAvailable()) return "Shizuku not available"
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        } catch (e: Exception) { "Shizuku error: ${e.message}" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Python bridge service
    // ─────────────────────────────────────────────────────────────────────────
    private fun startBridgeService() {
        val intent = Intent(this, BridgeService::class.java)
        startForegroundService(intent)
        bridgeClient = BridgeClient(this)
        bridgeClient?.connect("127.0.0.1", 7070)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JavaScript Interface — HTML calls these via Android.methodName()
    // ─────────────────────────────────────────────────────────────────────────
    inner class JsBridge {

        @JavascriptInterface
        fun executeCommand(cmd: String) {
            pushLog("Command received: $cmd", "cmd")
            pushToWeb(JSONObject().apply { put("task", cmd) })
            // Send to Python bridge
            scope.launch(Dispatchers.IO) {
                val result = bridgeClient?.sendCommand(cmd) ?: "Bridge not connected"
                withContext(Dispatchers.Main) {
                    pushLog(result, "ok")
                    pushToWeb(JSONObject().apply { put("task", "") }) // clear task
                }
            }
        }

        @JavascriptInterface
        fun openAccessibility() {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            pushLog("Accessibility settings opened", "action")
        }

        @JavascriptInterface
        fun bindShizuku() {
            if (Shizuku.pingBinder()) {
                requestShizukuPermission()
                pushLog("Shizuku permission requested", "action")
            } else {
                pushLog("Shizuku not installed — get it from Play Store", "warn")
                Toast.makeText(this@MainActivity, "Install Shizuku from Play Store", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun connectAdb() {
            scope.launch(Dispatchers.IO) {
                val result = runShizukuCommand("adb devices")
                withContext(Dispatchers.Main) {
                    pushLog("ADB: $result", "info")
                    pushStatus()
                }
            }
        }

        @JavascriptInterface
        fun connectBridge(ipPort: String) {
            val parts = ipPort.split(":")
            val host = parts.getOrElse(0) { "127.0.0.1" }
            val port = parts.getOrElse(1) { "7070" }.toIntOrNull() ?: 7070
            bridgeClient?.connect(host, port)
            pushLog("Connecting to Python bridge at $ipPort", "action")
        }

        @JavascriptInterface
        fun checkStatus() { pushStatus() }

        @JavascriptInterface
        fun newChat() {
            bridgeClient?.sendCommand("new chat")
            pushLog("Session memory cleared", "ok")
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            val vib = getSystemService(android.os.Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }

        @JavascriptInterface
        fun applySettings(settingsJson: String) {
            try {
                val s = JSONObject(settingsJson)
                pushLog("Settings updated", "ok")
                // Pass new API key / model to Python bridge
                bridgeClient?.sendCommand("__settings__:$settingsJson")
            } catch (e: Exception) {
                pushLog("Settings parse error", "error")
            }
        }

        @JavascriptInterface
        fun confirmResult(result: String) {
            bridgeClient?.sendCommand("__confirm__:$result")
        }

        @JavascriptInterface
        fun storeSave(key: String, value: String) {
            getSharedPreferences("rxcz", MODE_PRIVATE).edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun storeLoad(key: String): String {
            return getSharedPreferences("rxcz", MODE_PRIVATE).getString(key, "") ?: ""
        }
    }
}
