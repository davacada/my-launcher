package com.davacada.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional: lets edge lighting switch the screen straight off after a glow, the same as a press
 * of the power button. It reads nothing on screen and does nothing else.
 */
class EdgeOffService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: EdgeOffService? = null

        /** Turns the screen off. Returns false if the service isn't switched on. */
        fun screenOff(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        fun isEnabled(ctx: Context): Boolean {
            val flat = ComponentName(ctx, EdgeOffService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(flat, ignoreCase = true) }
        }
    }
}
