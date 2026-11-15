package org.fossify.phone.receivers

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import org.fossify.phone.R
import org.fossify.phone.activities.DialpadActivity
import org.fossify.phone.activities.MainActivity

/**
 * NovaDial-inspired handling of Telecom's standard missed-call broadcast.
 * Only the current default dialer issues notifications, avoiding duplicates.
 * Selecting "Call back" opens the number in our dialpad; it does not auto-dial.
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val tm = context.getSystemService(TelecomManager::class.java)
        if (tm?.defaultDialerPackage != context.packageName) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        if (count <= 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.dpad_missed_call_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ))

        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val title = if (count == 1) context.getString(R.string.dpad_one_missed_call)
            else context.getString(R.string.dpad_multiple_missed_calls, count)
        val viewIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            type = "vnd.android.cursor.dir/calls"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_missed_vector)
            .setContentTitle(title)
            .setContentText(number ?: context.getString(R.string.dpad_missed_call_unknown))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(PendingIntent.getActivity(
                context, 900, viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

        if (count == 1 && !number.isNullOrBlank()) {
            val callback = Intent(context, DialpadActivity::class.java).apply {
                action = Intent.ACTION_DIAL
                data = Uri.fromParts("tel", number, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            notification.addAction(
                R.drawable.ic_phone_vector,
                context.getString(R.string.dpad_missed_call_back),
                PendingIntent.getActivity(
                    context, 901, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        manager.notify(NOTIFICATION_ID, notification.build())
    }

    companion object {
        private const val CHANNEL_ID = "r77_missed_calls"
        private const val NOTIFICATION_ID = 7001
    }
}
