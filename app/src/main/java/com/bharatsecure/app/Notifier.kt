package com.bharatsecure.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bharatsecure.app.scam.ScamLevel
import com.bharatsecure.app.scam.ScamType
import com.bharatsecure.app.scam.ScamVerdict

object Notifier {
    private const val CHANNEL_CALL_ALERT = "call_alert"
    const val CHANNEL_PROTECTION = "protection"
    private const val CHANNEL_SCAM_ALERT = "scam_alert"

    private const val ID_INCOMING_CALL = 1001
    const val ID_PROTECTION = 1002
    private const val ID_SCAM_ALERT = 1003
    private val ALARM_VIBRATION = longArrayOf(0, 600, 200, 600, 200, 1000)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL_ALERT, "Incoming call alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Offers Protection Mode when a call comes in" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROTECTION, "Protection Mode", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a call is being recorded and transcribed" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAM_ALERT, "Scam warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns you when the call you're on looks like a scam"
                enableVibration(true)
                vibrationPattern = ALARM_VIBRATION
            }
        )
    }

    /** Loud warning while the call is still going on. */
    /** [reason]: an explanation from the laptop RAG server, shown instead of the generic advice. */
    fun showScamAlert(context: Context, verdict: ScamVerdict, reason: String? = null) {
        if (!canNotify(context) || verdict.level == ScamLevel.SAFE) return
        val type = verdict.type ?: ScamType.OTHER
        val title = if (verdict.level == ScamLevel.SURE) "🛑 SCAM CALL: ${type.label}"
        else "⚠️ Possible scam: ${type.label}"
        val openApp = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Opens the dialler with 1930 filled in; the user still presses call.
        val dial1930 = PendingIntent.getActivity(
            context, 3, Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SCAM_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(reason ?: type.advice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason ?: type.advice))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(ALARM_VIBRATION)
            .setColor(0xFFC62828.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_shield, "Call 1930 (Cyber helpline)", dial1930)
            .build()
        NotificationManagerCompat.from(context).notify(ID_SCAM_ALERT, notification)
    }

    fun showIncomingCall(context: Context, source: CallSource, caller: String) {
        if (!canNotify(context)) return
        if (ProtectionState.state.value.running) return

        val enableIntent = Intent(context, ProtectionLauncherActivity::class.java)
            .putExtra(ProtectionService.EXTRA_SOURCE, source.name)
            .putExtra(ProtectionService.EXTRA_CALLER, caller)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val enablePending = PendingIntent.getActivity(
            context, source.ordinal, enableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("${source.label} from $caller")
            .setContentText("Put the call on speaker and enable Protection Mode to record and check it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(enablePending)
            .addAction(R.drawable.ic_shield, "Enable Protection Mode", enablePending)
            .build()

        NotificationManagerCompat.from(context).notify(ID_INCOMING_CALL, notification)
    }

    fun cancelIncomingCall(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_INCOMING_CALL)
    }

    fun protectionNotification(context: Context, state: ProtectionUiState): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, ProtectionService::class.java).setAction(ProtectionService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lastLine = state.partial.ifBlank { state.transcript.lastOrNull().orEmpty() }
        val title = when (state.verdict.level) {
            ScamLevel.SURE -> "🛑 Scam call: ${state.verdict.type?.label}"
            ScamLevel.WARNING -> "⚠️ Possible scam: ${state.verdict.type?.label}"
            ScamLevel.SAFE -> "Protection Mode on: recording"
        }
        return NotificationCompat.Builder(context, CHANNEL_PROTECTION)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(lastLine.ifBlank { state.status })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lastLine.ifBlank { state.status }))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_shield, "Stop", stop)
            .build()
    }

    private fun canNotify(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
