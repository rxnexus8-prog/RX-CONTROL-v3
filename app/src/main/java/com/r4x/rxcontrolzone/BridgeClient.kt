package com.r4x.rxcontrolzone

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket

/**
 * TCP client that talks to the Python rx_control_zone.py script.
 * Python side should be running a simple socket server on port 7070.
 * Protocol: newline-delimited JSON.
 */
class BridgeClient(private val activity: MainActivity) {

    companion object { private const val TAG = "RXCZ_BRIDGE" }

    var connected = false
        private set

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    /**
     * Send a command string to Python. Returns the response line.
     */
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
     * Listen for push messages from Python (status updates, logs, confirms).
     */
    private suspend fun listenLoop() = withContext(Dispatchers.IO) {
        try {
            while (isActive && connected) {
                val line = reader?.readLine() ?: break
                try {
                    val json = JSONObject(line)
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
