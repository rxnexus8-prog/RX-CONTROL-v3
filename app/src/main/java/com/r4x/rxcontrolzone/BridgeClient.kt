package com.r4x.rxcontrolzone

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket

/**
 * V2 Bridge — handles two modes:
 * 1. cmd mode  : {"cmd":"open youtube"} → run on Python side (legacy)
 * 2. action mode: {"action":"click_text","text":"Search"} → execute via Accessibility
 */
class BridgeClient(private val activity: MainActivity) {

    companion object { private const val TAG = "RXCZ_BRIDGE" }

    var connected = false
        private set

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = ActionExecutor(activity)

    fun connect(host: String, port: Int) {
        scope.launch {
            try {
                disconnect()
                socket = Socket(host, port)
                val s = socket ?: return@launch
                writer = PrintWriter(s.outputStream, true)
                reader = BufferedReader(InputStreamReader(s.inputStream))
                connected = true
                activity.pushLog("Python bridge connected at $host:$port", "ok")
                activity.pushStatus()
                listenLoop()
            } catch (e: Exception) {
                connected = false
                Log.d(TAG, "Bridge connect failed: ${e.message}")
                activity.pushLog("Bridge not connected — start Python script first", "warn")
                activity.pushStatus()
            }
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; writer = null; reader = null
        connected = false
    }

    fun sendCommand(cmd: String): String {
        return try {
            if (!connected) return "Bridge not connected"
            val w = writer ?: return "Bridge not connected"
            val json = JSONObject().apply { put("cmd", cmd) }
            w.println(json.toString())
            reader?.readLine() ?: "No response"
        } catch (e: Exception) {
            connected = false
            activity.runOnUiThread { activity.pushStatus() }
            "Bridge error: ${e.message}"
        }
    }

    /**
     * Listen loop — handles both push messages from Python AND
     * action requests that APK should execute via Accessibility.
     */
    private suspend fun listenLoop() = withContext(Dispatchers.IO) {
        try {
            while (isActive && connected) {
                val line = reader?.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)

                    // ── Action request from Python → execute via Accessibility ──
                    if (json.has("action")) {
                        val requestId = json.optString("request_id", "")
                        scope.launch(Dispatchers.Main) {
                            val result = executor.execute(json)
                            if (requestId.isNotEmpty()) result.put("request_id", requestId)
                            // Send result back to Python
                            withContext(Dispatchers.IO) {
                                try {
                                    writer?.println(result.toString())
                                } catch (_: Exception) {}
                            }
                        }
                        continue
                    }

                    // ── Push update from Python → update WebView ──
                    withContext(Dispatchers.Main) { activity.pushToWeb(json) }

                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        connected = false
        withContext(Dispatchers.Main) {
            activity.pushLog("Bridge disconnected", "warn")
            activity.pushStatus()
        }
    }
}
