package com.bharatsecure.app

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Detects WhatsApp voice/video calls from WhatsApp's own notifications.
 * Needs "Notification access" turned on for BharatSecure in system settings.
 */
class WhatsAppCallListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    // One offer per call: the incoming and ongoing call notifications both match.
    private var alertedThisCall = false

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isWhatsAppCall(sbn)) return
        if (alertedThisCall || ProtectionState.state.value.running) return
        alertedThisCall = true
        val caller = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: "WhatsApp caller"
        Notifier.showIncomingCall(this, CallSource.WHATSAPP, caller)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isWhatsAppCall(sbn)) return
        // When a call is answered WhatsApp swaps the "Incoming call" notification
        // for an "Ongoing call" one, so wait a moment before deciding it has ended.
        handler.postDelayed({
            val stillInCall = try {
                activeNotifications?.any { isWhatsAppCall(it) } == true
            } catch (e: SecurityException) {
                false
            }
            if (!stillInCall) {
                alertedThisCall = false
                Notifier.cancelIncomingCall(this)
                ProtectionService.stopIfSource(this, CallSource.WHATSAPP)
            }
        }, END_CHECK_DELAY_MS)
    }

    private fun isWhatsAppCall(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return false
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) return true
        return callText(sbn).contains("call")
    }

    private fun callText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).joinToString(" ").lowercase()
    }

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private const val END_CHECK_DELAY_MS = 3_000L
    }
}
