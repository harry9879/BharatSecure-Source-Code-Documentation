package com.bharatsecure.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Listens for normal (SIM) calls. When the phone rings we offer Protection Mode;
 * when the call ends we stop any recording that belongs to a phone call.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        // The number only arrives when READ_CALL_LOG is granted; Android sends the
        // broadcast twice (with and without it), so ignore the copy with no number
        // if we already alerted for this ring.
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (number == null && lastRingAlerted) return
                lastRingAlerted = true
                Notifier.showIncomingCall(context, CallSource.PHONE, number ?: "Unknown number")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                lastRingAlerted = false
                Notifier.cancelIncomingCall(context)
                ProtectionService.stopIfSource(context, CallSource.PHONE)
            }
            // OFFHOOK: call answered. Leave the offer notification up so the
            // user can still enable protection after picking up.
        }
    }

    companion object {
        private var lastRingAlerted = false
    }
}
