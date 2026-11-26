package com.rachitratnammudaliar.kitchtally.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rachitratnammudaliar.kitchtally.MainActivity
import com.rachitratnammudaliar.kitchtally.data.AppDatabase
import com.rachitratnammudaliar.kitchtally.data.Prefs
import com.rachitratnammudaliar.kitchtally.domain.Expiry
import com.rachitratnammudaliar.kitchtally.ui.PantryState
import java.time.LocalDate
import java.time.ZoneId

/**
 * A phone notification a few days before something's best-before date, at 9:00.
 * Everything happens on the phone: nothing is sent anywhere, and the app still has no internet permission.
 */
object ExpiryReminders {
    private const val CHANNEL = "expiry"

    /** Android 13 and later ask before an app may show notifications. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context, itemId: Long, name: String = "", text: String = ""): PendingIntent {
        val intent = Intent(context, ExpiryReceiver::class.java)
            .setAction("kitchtally.expiry.$itemId")
            .putExtra("name", name)
            .putExtra("text", text)
        return PendingIntent.getBroadcast(context, itemId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Sets one alarm per item that has a best-before date still ahead, and cancels the ones that no longer apply. */
    fun schedule(context: Context, state: PantryState, prefs: Prefs) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val before = prefs.expiryDaysBefore.toLong()
        val keep = mutableSetOf<String>()
        if (prefs.expiryReminders) {
            val now = System.currentTimeMillis()
            for (item in state.items) {
                val expiry = Expiry.of(state.entries(item.id), state.stock(item.id), item.expiryDay) ?: continue
                val at = expiry.minusDays(before).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at <= now) continue
                val text = "Best before " + Expiry.label(before) + ". Use it first."
                runCatching { alarms.set(AlarmManager.RTC_WAKEUP, at, pending(context, item.id, item.name, text)) }
                keep += item.id.toString()
            }
        }
        for (old in prefs.scheduledExpiryIds - keep) {
            val id = old.toLongOrNull() ?: continue
            runCatching { alarms.cancel(pending(context, id)) }
        }
        prefs.scheduledExpiryIds = keep
    }

    internal fun show(context: Context, name: String, text: String) {
        if (needsPermission(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Best-before reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(name.ifBlank { "Something is about to expire" })
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(name.hashCode(), notification)
    }
}

class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ExpiryReminders.show(context, intent.getStringExtra("name").orEmpty(), intent.getStringExtra("text").orEmpty())
    }
}

/** Alarms are cleared when the phone restarts, so set them again. */
class ExpiryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val prefs = Prefs(app)
                if (prefs.expiryReminders) {
                    val dao = AppDatabase.get(app).pantry()
                    val state = kotlinx.coroutines.runBlocking { PantryState(dao.items(), dao.events()) }
                    ExpiryReminders.schedule(app, state, prefs)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
