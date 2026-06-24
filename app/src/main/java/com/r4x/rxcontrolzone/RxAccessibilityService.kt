package com.r4x.rxcontrolzone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * RX CONTROL ZONE — Accessibility Service
 *
 * Yeh service UI element tree ko scan karti hai aur
 * coordinates ke bina elements dhoondh ke click/type/scroll karti hai.
 * Non-blocking: sab kuch background coroutine mein hota hai.
 */
class RxAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RxAccessibilityService? = null
            private set
        private const val TAG = "RXCZ_ACC"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to events here — we act on demand from commands
        // This keeps the service non-blocking and prevents UI freezes
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API — called from BridgeService / Python commands
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Find element by text/contentDescription and click it.
     * Returns true if found and clicked.
     */
    suspend fun clickByText(text: String): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val node = findNodeByText(root, text)
        if (node != null) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                // fallback: click centre of bounds
                val rect = Rect()
                node.getBoundsInScreen(rect)
                tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            node.recycle()
            root.recycle()
            true
        } else {
            root.recycle()
            false
        }
    }

    /**
     * Find a text input field and type text into it.
     */
    suspend fun typeIntoField(hint: String, text: String): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val node = findEditText(root, hint)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(150)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            node.recycle()
            root.recycle()
            ok
        } else {
            root.recycle()
            false
        }
    }

    /**
     * Tap at absolute screen coordinates (used as fallback).
     */
    fun tapAt(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * Swipe gesture.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Press back button.
     */
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Go home.
     */
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Open notifications.
     */
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * Scroll in a given direction (up/down) on the focused scrollable view.
     */
    suspend fun scroll(direction: String): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val scrollable = findScrollable(root)
        val action = if (direction == "down")
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = scrollable?.performAction(action) ?: false
        scrollable?.recycle()
        root.recycle()
        ok
    }

    /**
     * Get all visible text on screen — sent to AI for context.
     */
    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        root.recycle()
        return sb.toString().take(3000) // cap to avoid huge payloads
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        val lower = text.lowercase()
        // Try exact text match first
        val byText = node.findAccessibilityNodeInfosByText(text)
        if (byText.isNotEmpty()) return byText[0]
        // Recurse
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val label = (child.text?.toString() ?: child.contentDescription?.toString() ?: "").lowercase()
            if (label.contains(lower)) return child
            val found = findNodeByText(child, text)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findEditText(
        node: AccessibilityNodeInfo,
        hint: String
    ): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) {
            if (hint.isEmpty()) return node
            val label = (node.text?.toString() ?: node.hintText?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
            if (label.contains(hint.lowercase())) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child, hint)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.toString()?.let { if (it.isNotBlank()) sb.appendLine(it.trim()) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) sb.appendLine(it.trim()) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }
}
