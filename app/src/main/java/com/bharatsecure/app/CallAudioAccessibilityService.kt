package com.bharatsecure.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * Android silences the microphone for ordinary apps while a call is active.
 * Apps with an enabled accessibility service whose window is on screen are
 * exempt, so while Protection Mode runs this service shows a small badge on
 * top of the call screen. It does not read the screen.
 */
class CallAudioAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var badge: View? = null

    override fun onServiceConnected() {
        instance = this
        if (ProtectionState.state.value.running) showBadge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hideBadge()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun showBadge() = handler.post {
        if (badge != null) return@post
        val view = TextView(this).apply {
            text = "🛡 Protection ON"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(28, 14, 28, 14)
            setBackgroundColor(0xE6C62828.toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 160
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        badge = view
    }

    private fun hideBadge() = handler.post {
        badge?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        badge = null
    }

    companion object {
        @Volatile private var instance: CallAudioAccessibilityService? = null

        fun setBadgeVisible(visible: Boolean) {
            val service = instance ?: return
            if (visible) service.showBadge() else service.hideBadge()
        }

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, CallAudioAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
