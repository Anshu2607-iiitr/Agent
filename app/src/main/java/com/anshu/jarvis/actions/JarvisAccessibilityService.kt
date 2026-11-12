package com.anshu.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Jarvis's "robot hands": can press system buttons, read what's on screen,
 * click any button by its text, type, scroll and flip Quick Settings tiles.
 * User must enable it in Settings > Accessibility.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- screen reading helpers ----------

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try { windows.mapNotNullTo(list) { it.root } } catch (_: Exception) {}
        rootInActiveWindow?.let { list.add(0, it) }
        return list
    }

    private fun norm(s: CharSequence?) = s?.toString()?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()

    private fun nodeLabel(n: AccessibilityNodeInfo) = norm(n.text) + " " + norm(n.contentDescription)

    /** Breadth-first search for the first node whose text/description contains any of [words]. */
    fun find(words: List<String>, predicate: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo? {
        val targets = words.map { norm(it) }.filter { it.isNotEmpty() }
        for (exact in listOf(true, false)) {
            for (root in roots()) {
                val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    val t = norm(n.text)
                    val d = norm(n.contentDescription)
                    val hit = targets.any { w ->
                        if (exact) t == w || d == w else t.contains(w) || d.contains(w)
                    }
                    if (hit && n.isVisibleToUser && predicate(n)) return n
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        // Not clickable in the tree → tap its centre with a gesture
        val r = Rect().also { node.getBoundsInScreen(it) }
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    private fun swipe(up: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (up) dm.heightPixels * 0.75f to dm.heightPixels * 0.25f
                       else dm.heightPixels * 0.25f to dm.heightPixels * 0.75f
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(),
            null, null
        )
    }

    companion object {
        @Volatile private var instance: JarvisAccessibilityService? = null
        private val main = Handler(Looper.getMainLooper())
        private const val NEED = "Turn on Jarvis Controls in Accessibility settings first"

        fun isOn() = instance != null

        private fun global(action: Int, ok: String): String {
            val s = instance ?: return NEED
            return if (s.performGlobalAction(action)) ok else "That isn't supported on this phone"
        }

        fun lock(): String =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) "Not supported on this phone"
            else global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking the phone")

        fun powerMenu() = global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Opening power menu")
        fun back() = global(AccessibilityService.GLOBAL_ACTION_BACK, "Going back")
        fun home() = global(AccessibilityService.GLOBAL_ACTION_HOME, "Going home")
        fun recents() = global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps")
        fun notifications() = global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opening notifications")
        fun quickSettings() = global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Opening quick settings")

        fun screenshot(): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "Not supported on this phone"
            val s = instance ?: return NEED
            // Small delay so the reply sound doesn't matter and the shade is closed
            main.postDelayed({ s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 600)
            return "Taking screenshot"
        }

        fun click(label: String): String {
            val s = instance ?: return NEED
            val node = s.find(listOf(label)) ?: return "I can't see $label on the screen"
            return if (s.clickNode(node)) "Clicked $label" else "Couldn't click $label"
        }

        fun clickLater(label: String, delayMs: Long) {
            main.postDelayed({
                val s = instance ?: return@postDelayed
                s.find(listOf(label))?.let { s.clickNode(it) }
            }, delayMs)
        }

        /** After a YouTube search opens, tap the first result so it plays. */
        fun clickFirstVideoLater(delayMs: Long) {
            main.postDelayed({
                val s = instance ?: return@postDelayed
                val dm = s.resources.displayMetrics
                // First video thumbnail sits roughly in the upper-middle of the results list
                s.tap(dm.widthPixels / 2f, dm.heightPixels * 0.32f)
            }, delayMs)
        }

        fun type(text: String): String {
            val s = instance ?: return NEED
            val field = s.roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                ?: s.roots().firstNotNullOfOrNull { findEditable(it) }
                ?: return "Tap a text box first, then tell me what to type"
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed it"
            else "Couldn't type there"
        }

        fun scroll(down: Boolean): String {
            val s = instance ?: return NEED
            // Prefer a real scrollable list; fall back to a swipe gesture
            val list = s.roots().firstNotNullOfOrNull { root -> findScrollable(root) }
            val ok = list?.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ) ?: false
            if (!ok) s.swipe(up = down)
            return if (down) "Scrolling down" else "Scrolling up"
        }

        private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                if (n.isEditable && n.isVisibleToUser) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            return null
        }

        private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                if (n.isScrollable && n.isVisibleToUser) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            return null
        }

        /**
         * Flip a Quick Settings tile (Wi-Fi, Bluetooth, Hotspot, Airplane…) like a human would:
         * open the shade, find the tile by its name, tap it, close the shade.
         */
        fun toggleTile(names: List<String>, spoken: String, wantOn: Boolean?): String {
            val s = instance ?: return NEED
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            main.postDelayed({
                val tile = s.find(names)
                if (tile != null) {
                    val state = (if (Build.VERSION.SDK_INT >= 30) tile.stateDescription else null)
                        ?.toString()?.lowercase().orEmpty()
                    val isOn = tile.isChecked || state.startsWith("on")
                    val known = tile.isCheckable || state.isNotEmpty()
                    if (wantOn == null || !known || isOn != wantOn) s.clickNode(tile)
                }
                main.postDelayed({
                    if (Build.VERSION.SDK_INT >= 31)
                        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    else {
                        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }, 900)
            }, 900)
            return when (wantOn) {
                true -> "Turning on $spoken"
                false -> "Turning off $spoken"
                null -> "Toggling $spoken"
            }
        }
    }
}
