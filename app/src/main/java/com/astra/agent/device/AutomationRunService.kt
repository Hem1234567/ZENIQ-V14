package com.astra.agent.device

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.astra.agent.Alarms
import com.astra.agent.MainActivity
import com.astra.agent.R
import com.astra.agent.SettingsStore
import com.astra.agent.agent.ToolRegistry
import com.astra.agent.hub.AutoPilot
import com.astra.agent.hub.AutoRun
import com.astra.agent.hub.AutomationStore
import com.astra.agent.hub.Hub
import com.astra.agent.hub.PlanJson
import com.astra.agent.hub.SavedAutomation
import com.astra.agent.hub.StepKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Headless run of a saved automation, started by an exact alarm when the card is set to "Run automatically".
 * Live step progress is written to the notification; the result is stored in the card history by AutoPilot.
 */
class AutomationRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.init(applicationContext)
        val id = intent?.getStringExtra(Alarms.EXTRA_AUTOMATION).orEmpty()
        val a = AutomationStore.get(id)
        if (a == null) { stopSelf(); return START_NOT_STICKY }
        ensureChannel(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, progress(a.id, a.name, "Starting\u2026"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, progress(a.id, a.name, "Starting\u2026"))
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!claimed.add(id)) { stopSelf(); return START_NOT_STICKY }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeniq:automation").apply { acquire(30 * 60 * 1000L) }
        scope.launch {
            try { run(a) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AutomationStore.setStatus(a.id, "failed"); Alarms.notifyFailure(this@AutomationRunService, a, e.message ?: e.javaClass.simpleName) }
            finally { claimed.remove(id); try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; stopSelf() }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(a: SavedAutomation) {
        val plan = AutomationStore.plan(a.id) ?: AutoPilot.quickPlan(a.request)
        val settings = SettingsStore(this).load()
        val needsPhone = plan.steps.any { it.kind == StepKind.DEVICE || it.kind == StepKind.BROWSER }
        if (needsPhone && !AutoPilot.accessibilityOn()) {
            AutomationStore.setStatus(a.id, "failed")
            Alarms.notifyFailure(this, a, "Accessibility is off, so ZENIQ cannot control the screen. Open ZENIQ and turn it on, then run this automation again.")
            return
        }
        if (needsPhone) {
            val locked = try { (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked } catch (_: Exception) { false }
            if (locked) {
                AutomationStore.setStatus(a.id, "needs_unlock")
                Alarms.notifyUnlockNeeded(this, a)
                return
            }
        }
        val run = AutoRun(a.request, PlanJson.fresh(plan), false).also { it.cardId = a.id }
        val watcher = scope.launch { run.tick.collect { updateProgress(a.id, a.name, run) } }
        try { AutoPilot.execute(this, run, settings, ToolRegistry(this)) }
        finally { watcher.cancel() }
        notifyResult(a, run)
    }

    private fun updateProgress(id: String, name: String, run: AutoRun) {
        val steps = run.plan.steps.filter { it.kind != StepKind.VERIFY }
        val done = steps.count { it.status == "completed" || it.status == "failed" || it.status == "skipped" }
        val current = steps.firstOrNull { it.status == "running" || it.status == "recovering" }
        val text = when {
            run.status != "running" -> "Finished: " + run.status.replace('_', ' ')
            current != null -> "${done + 1}/${steps.size} \u00B7 ${current.title}"
            else -> "$done/${steps.size} steps"
        }
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, progress(id, name, text)) } catch (_: Exception) { }
    }

    private fun notifyResult(a: SavedAutomation, run: AutoRun) {
        val ok = run.status == "completed"
        val text = if (ok) "Done" + (if (run.verification.isNotEmpty()) " \u00B7 ${run.verification}" else "") else run.errorOrResult()
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeniq)
            .setContentTitle("ZENIQ: ${a.name}")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(run.finalText.take(500).ifBlank { text }))
            .setContentIntent(openCard(a.id))
            .setAutoCancel(true).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(a.id.hashCode() + 3, n) } catch (_: Exception) {}
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    private fun AutoRun.errorOrResult(): String {
        val failed = plan.steps.firstOrNull { it.error.isNotBlank() }?.error
        return failed ?: finalText.take(160).ifBlank { "Run failed." }
    }

    private fun progress(id: String, name: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeniq).setContentTitle("ZENIQ: $name")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(0, 0, true).setContentIntent(openCard(id)).build()

    private fun openCard(id: String): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Alarms.EXTRA_AUTOMATION, id)
        return PendingIntent.getActivity(this, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "zeniq_automation_runs"
        private const val NOTIF_ID = 21
        private val claimed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        fun start(ctx: Context, automationId: String): Boolean = try {
            ctx.startForegroundService(Intent(ctx, AutomationRunService::class.java).putExtra(Alarms.EXTRA_AUTOMATION, automationId))
            true
        } catch (e: Exception) {
            val a = AutomationStore.get(automationId)
            if (a != null) Alarms.notifyFailure(ctx, a, "Android blocked the background run. Open ZENIQ and run it from the card.")
            false
        }
    }

    private fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Automation runs", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
