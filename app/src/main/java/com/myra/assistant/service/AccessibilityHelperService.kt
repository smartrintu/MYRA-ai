package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        @Volatile var instance: AccessibilityHelperService? = null

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(ctx.packageName) && enabled.contains("AccessibilityHelperService")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Goes to the home screen (apps cannot be force-closed by accessibility). */
    fun closeCurrentApp(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun clickOnText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Clicks the first view whose content description contains any of the needles (case-insensitive). */
    fun clickFirstWithDescription(vararg needles: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, needles.map { it.lowercase() })
    }

    private fun findAndClick(node: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val d = node.contentDescription?.toString()?.lowercase()
        if (d != null && needles.any { d.contains(it) }) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (findAndClick(c, needles)) return true
        }
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipeUp(): Boolean {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.2f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 350L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tapAtFraction(fx: Float, fy: Float): Boolean {
        val dm = resources.displayMetrics
        return tapAt(dm.widthPixels * fx, dm.heightPixels * fy)
    }

    fun readScreenText(maxChars: Int = 1500): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb, maxChars)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, max: Int) {
        if (sb.length >= max) return
        val t = node.text?.toString() ?: node.contentDescription?.toString()
        if (!t.isNullOrBlank()) sb.append(t.trim()).append(" | ")
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectText(c, sb, max)
        }
    }

    fun globalActionByName(name: String): Boolean = when (name.lowercase()) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
        else -> false
    }

    fun scrollDown(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollUp(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scroll(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findScrollable(root) ?: return false
        return node.performAction(action)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }
}
