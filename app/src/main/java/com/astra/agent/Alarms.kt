package com.astra.agent

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.astra.agent.hub.AutomationStore
import com.astra.agent.hub.RepeatType
import com.astra.agent.hub.RunMode
import com.astra.agent.hub.SavedAutomation
import com.astra.agent.hub.Schedules
import java.time.ZonedDateTime

/**
 * Exact-alarm scheduling for automation cards. AutomationStore is the only source of truth; this object only
 * turns its schedules into alarms, re-arms them after reboots / updates / clock changes, and reports missed runs.
 */
object Alarms {
    const val EXTRA_AUTOMATION = "zeniq_automation"
    const val EXTRA_AUTORUN = "zeniq_automation_run"
    const val ACTION_FIRE = "com.astra.agent.action.FIRE_AUTOMATION"
    private const val CHANNEL = "zeniq_automations"

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() } catch (_: Exception) { false }
    }

    /** Opens the system page where the user allows exact alarms (Android 12+). */
    fun exactAlarmSettings(ctx: Context) {
        try {
            val i = if (Build.VERSION.SDK_INT >= 31) Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${ctx.packageName}"))
            else Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${ctx.packageName}"))
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun notificationsBlocked(ctx: Context): Boolean = try { !NotificationManagerCompat.from(ctx).areNotificationsEnabled() } catch (_: Exception) { false }

    fun notificationSettings(ctx: Context) {
        try {
            val i = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun batteryOptimized(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName).not()
        } catch (_: Exception) { false }
    }

    fun batterySettings(ctx: Context) {
        try { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun pending(ctx: Context, automationId: String): PendingIntent {
        val i = Intent(ctx, TaskReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_AUTOMATION, automationId)
        return PendingIntent.getBroadcast(ctx, automationId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Arms the next occurrence of one card. Returns true when an alarm is set. */
    fun arm(ctx: Context, a: SavedAutomation): Boolean {
        val s = a.schedule ?: return false
        cancel(ctx, a.id)
        if (!s.enabled) return false
        val now = ZonedDateTime.now()
        val next = Schedules.nextRun(s, now) ?: run {
            // a `once` card whose time passed: keep it, but do not pretend an alarm exists
            AutomationStore.setNextRun(a.id, 0L)
            return false
        }
        val at = next.toInstant().toEpochMilli()
        AutomationStore.setNextRun(a.id, at)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, a.id)
        val exact = canScheduleExact(ctx)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            return true
        } catch (_: SecurityException) {
            return try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); true } catch (_: Exception) { false }
        } catch (_: Exception) { return false }
    }

    fun armAll(ctx: Context): Int {
        var n = 0
        for (a in AutomationStore.list()) if (a.schedule?.enabled == true && arm(ctx, a)) n++
        return n
    }

    fun cancel(ctx: Context, automationId: String) {
        try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, automationId)) } catch (_: Exception) {}
    }

    /** App start and system broadcasts: record missed occurrences, then re-arm everything. */
    fun onAppStart(ctx: Context) {
        try { AutomationStore.checkMissed() } catch (_: Exception) {}
        try { armAll(ctx) } catch (_: Exception) {}
    }

    private fun channel(ctx: Context, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    private fun openCard(ctx: Context, id: String, run: Boolean): PendingIntent {
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_AUTOMATION, id).putExtra(EXTRA_AUTORUN, run)
        return PendingIntent.getActivity(ctx, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** "Just notify me": tapping the notification opens the saved card and runs it (never a chat prompt). */
    fun notifyScheduled(ctx: Context, a: SavedAutomation): Boolean {
        if (notificationsBlocked(ctx)) return false
        channel(ctx, CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeniq).setContentTitle("ZENIQ: ${a.name}")
            .setContentText("Tap to run this automation now.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.request.take(300) + "\n\nTap to run it now."))
            .setContentIntent(openCard(ctx, a.id, true)).setAutoCancel(true).build()
        return try { NotificationManagerCompat.from(ctx).notify(a.id.hashCode(), n); true } catch (_: SecurityException) { false }
    }

    fun notifyUnlockNeeded(ctx: Context, a: SavedAutomation): Boolean {
        if (notificationsBlocked(ctx)) return false
        channel(ctx, CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeniq).setContentTitle("ZENIQ: ${a.name}")
            .setContentText("Needs your phone unlocked. Tap to run.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${a.name} needs your phone unlocked to control the screen. Tap to run it now."))
            .setContentIntent(openCard(ctx, a.id, true)).setAutoCancel(true).build()
        return try { NotificationManagerCompat.from(ctx).notify(a.id.hashCode() + 1, n); true } catch (_: SecurityException) { false }
    }

    fun notifyFailure(ctx: Context, a: SavedAutomation, why: String): Boolean {
        if (notificationsBlocked(ctx)) return false
        channel(ctx, CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeniq).setContentTitle("ZENIQ: ${a.name}")
            .setContentText(why.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(why))
            .setContentIntent(openCard(ctx, a.id, false)).setAutoCancel(true).build()
        return try { NotificationManagerCompat.from(ctx).notify(a.id.hashCode() + 2, n); true } catch (_: SecurityException) { false }
    }
}

/** Alarm broadcast: records the occurrence, then notifies the user or starts the headless run. */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Alarms.ACTION_FIRE) return
        val id = intent.getStringExtra(Alarms.EXTRA_AUTOMATION) ?: return
        val a = AutomationStore.get(id) ?: return
        val s = a.schedule ?: return
        if (!s.enabled) return
        val now = ZonedDateTime.now()
        val fired = s.nextRun.takeIf { it > 0 } ?: now.toInstant().toEpochMilli()
        val next = Schedules.nextRun(s.copy(lastRun = fired, dateEpochDay = if (s.repeat == RepeatType.ONCE && s.dateEpochDay == 0L) now.toLocalDate().toEpochDay() else s.dateEpochDay), now)
        AutomationStore.markFired(id, fired, next?.toInstant()?.toEpochMilli() ?: 0L, next == null)
        when (s.runMode) {
            RunMode.NOTIFY -> Alarms.notifyScheduled(ctx, a)
            RunMode.AUTO -> com.astra.agent.device.AutomationRunService.start(ctx, id)
        }
        if (next != null) AutomationStore.get(id)?.let { Alarms.arm(ctx, it) }
    }
}

/** Re-arms every schedule after boot, an app update, a clock change or a time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Alarms.onAppStart(ctx)
        }
    }
}
