package com.bharatsecure.app

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * SMS Shield = the team's separate ScamShield SMS app. BharatSecure protects
 * calls; ScamShield is the default SMS app that checks every message. The two
 * stay separate apps; this only opens the other one.
 */
object SmsShield {
    // Release build, then the debug build (ScamShield's build adds ".debug").
    // Both are also listed under <queries> in the manifest.
    private val PACKAGES = listOf("com.scamshield.sms", "com.scamshield.sms.debug")

    private fun launchIntent(context: Context): Intent? =
        PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

    fun isInstalled(context: Context): Boolean = launchIntent(context) != null

    /** Opens ScamShield, or explains how to get it. Returns true if it opened. */
    fun open(context: Context): Boolean {
        val intent = launchIntent(context)
        if (intent == null) {
            Toast.makeText(context, "SMS Shield (ScamShield) isn't installed on this phone yet.", Toast.LENGTH_LONG).show()
            return false
        }
        context.startActivity(intent)
        return true
    }
}
