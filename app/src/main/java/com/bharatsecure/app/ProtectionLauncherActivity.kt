package com.bharatsecure.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity behind the "Enable Protection Mode" notification button.
 * It starts the recording service while the app is visibly in the foreground,
 * which Android requires for a microphone foreground service, then closes.
 */
class ProtectionLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(ProtectionService.EXTRA_SOURCE)
            ?.let { runCatching { CallSource.valueOf(it) }.getOrNull() } ?: CallSource.MANUAL
        val caller = intent.getStringExtra(ProtectionService.EXTRA_CALLER)

        if (ProtectionService.start(this, source, caller)) {
            Notifier.cancelIncomingCall(this)
            Toast.makeText(this, "Protection Mode on. Put the call on speaker.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Allow microphone access in BharatSecure first.", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
