package com.anshu.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** Phase 6: system-level powers. User must enable it in Settings > Accessibility. */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private var instance: JarvisAccessibilityService? = null

        fun lock(): Boolean {
            val s = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }

        fun powerMenu(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        }
    }
}
