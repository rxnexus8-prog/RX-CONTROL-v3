package com.r4x.rxcontrolzone

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Receives structured action JSON from Python and executes via
 * AccessibilityService — no screenshots, no fixed coordinates.
 *
 * Protocol (Python → APK):
 * {"action":"click_text","text":"Search"}
 * {"action":"click_id","id":"search_button"}
 * {"action":"type","text":"hello world"}
 * {"action":"tap","x":540,"y":300}
 * {"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300}
 * {"action":"key","key":"back"|"home"|"enter"|"notifications"}
 * {"action":"open_app","package":"com.android.chrome"}
 * {"action":"get_screen_text"}
 * {"action":"scroll","direction":"down"|"up"}
 *
 * APK → Python reply:
 * {"ok":true,"result":"done"}
 * {"ok":false,"error":"element not found"}
 * {"ok":true,"result":"screen text here..."}  // for get_screen_text
 */
class ActionExecutor(private val activity: MainActivity) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    suspend fun execute(json: JSONObject): JSONObject {
        val action = json.optString("action", "")
        val acc = RxAccessibilityService.instance

        return when (action) {

            "click_text" -> {
                val text = json.optString("text", "")
                if (acc == null) return err("Accessibility Service not running")
                val ok = acc.clickByText(text)
                if (ok) ok("clicked: $text")
                else {
                    // Fallback: try tap with coordinates if provided
                    val x = json.optInt("x", -1)
                    val y = json.optInt("y", -1)
                    if (x > 0 && y > 0) {
                        acc.tapAt(x.toFloat(), y.toFloat())
                        ok("tapped fallback at $x,$y")
                    } else err("Element not found: $text")
                }
            }

            "click_id" -> {
                val id = json.optString("id", "")
                if (acc == null) return err("Accessibility Service not running")
                val ok = acc.clickByText(id)
                if (ok) ok("clicked id: $id") else err("Id not found: $id")
            }

            "type" -> {
                val text = json.optString("text", "")
                val hint = json.optString("hint", "")
                if (acc == null) return err("Accessibility Service not running")
                val ok = acc.typeIntoField(hint, text)
                if (ok) ok("typed: $text") else {
                    // Fallback: use ADB input text
                    val safe = text.replace(" ", "%s")
                    val r = activity.runShizukuCommand("input text $safe")
                    ok("typed via adb: $r")
                }
            }

            "tap" -> {
                val x = json.optInt("x", 0).toFloat()
                val y = json.optInt("y", 0).toFloat()
                if (acc == null) return err("Accessibility not running")
                var result = ""
                acc.tapAt(x, y) { success -> result = if (success) "tapped $x,$y" else "tap failed" }
                delay(200)
                ok("tapped $x,$y")
            }

            "swipe" -> {
                val x1 = json.optInt("x1", 0).toFloat()
                val y1 = json.optInt("y1", 0).toFloat()
                val x2 = json.optInt("x2", 0).toFloat()
                val y2 = json.optInt("y2", 0).toFloat()
                val dur = json.optLong("duration_ms", 300)
                if (acc == null) return err("Accessibility not running")
                acc.swipe(x1, y1, x2, y2, dur)
                delay(dur + 100)
                ok("swiped")
            }

            "key" -> {
                val key = json.optString("key", "back")
                if (acc == null) return err("Accessibility not running")
                when (key.lowercase()) {
                    "back"          -> acc.pressBack()
                    "home"          -> acc.pressHome()
                    "notifications" -> acc.openNotifications()
                    "enter"         -> activity.runShizukuCommand("input keyevent KEYCODE_ENTER")
                    "volume_up"     -> activity.runShizukuCommand("input keyevent KEYCODE_VOLUME_UP")
                    "volume_down"   -> activity.runShizukuCommand("input keyevent KEYCODE_VOLUME_DOWN")
                    else            -> activity.runShizukuCommand("input keyevent $key")
                }
                delay(300)
                ok("key: $key")
            }

            "open_app" -> {
                val pkg = json.optString("package", "")
                if (pkg.isEmpty()) return err("package name required")
                val r = activity.runShizukuCommand(
                    "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                )
                delay(1500)
                ok("opened: $pkg — $r")
            }

            "get_screen_text" -> {
                if (acc == null) return err("Accessibility not running")
                val text = acc.getScreenText()
                JSONObject().apply {
                    put("ok", true)
                    put("result", text)
                }
            }

            "scroll" -> {
                val dir = json.optString("direction", "down")
                if (acc == null) return err("Accessibility not running")
                val ok = acc.scroll(dir)
                if (ok) ok("scrolled $dir") else err("scroll failed")
            }

            "wait" -> {
                val ms = json.optLong("ms", 1000)
                delay(ms)
                ok("waited ${ms}ms")
            }

            else -> err("Unknown action: $action")
        }
    }

    private fun ok(msg: String) = JSONObject().apply {
        put("ok", true); put("result", msg)
    }
    private fun err(msg: String) = JSONObject().apply {
        put("ok", false); put("error", msg)
    }
}
