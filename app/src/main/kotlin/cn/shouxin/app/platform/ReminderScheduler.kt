package cn.shouxin.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.*
import cn.shouxin.app.data.AppDatabase
import cn.shouxin.core.*
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import cn.shouxin.app.data.MetaRow

class ReminderScheduler(private val context: Context, private val db: AppDatabase) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private fun intent(kind: String, id: String) = Intent(context, ReminderReceiver::class.java)
        .setAction("cn.shouxin.$kind").setData(Uri.parse("shouxin://schedule/$kind/$id"))
    private fun pending(i: Intent) = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(at: Long, operation: PendingIntent) {
        // Special access is optional. Revocation/denial must not make saving a rule fail.
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation); return }
            catch (_: SecurityException) { /* Fall back if permission changed while scheduling. */ }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }
    fun cancelRule(id: String) { alarm.cancel(pending(intent("TIME", id))) }
    fun cancelSnooze(key: String) { alarm.cancel(pending(intent("SNOOZE", key))) }
    fun cancelFocus(id: Long) { alarm.cancel(pending(intent("FOCUS", id.toString()))) }
    suspend fun markTimeHandled(id: String?, version: Int, at: Long) = mutex.withLock {
        if (id != null && db.dao().meta("schedule:$id") == "$version:$at") db.dao().saveMeta(MetaRow("schedule:$id", ""))
    }
    suspend fun invalidateTimePlans() = mutex.withLock {
        db.dao().rules().forEach { cancelRule(it.id); db.dao().saveMeta(MetaRow("schedule:${it.id}", "")) }
    }
    suspend fun reconcile() = mutex.withLock {
        val now = System.currentTimeMillis()
        for (row in db.dao().rules()) {
            val rule = row.model()
            if (!rule.enabled || rule.type !in setOf(RuleType.FIXED_TIME, RuleType.INTERVAL)) {
                cancelRule(rule.id); db.dao().saveMeta(MetaRow("schedule:${rule.id}", "")); continue
            }
            val stored = db.dao().meta("schedule:${rule.id}").orEmpty().split(':')
            val pendingAt = stored.getOrNull(1)?.toLongOrNull()
            // Keep an inexact alarm that is due but still inside the five-minute delivery window.
            // Maintenance must not cancel it merely because its nominal time has passed.
            val next = pendingAt?.takeIf { stored.firstOrNull()?.toIntOrNull() == rule.version && it + 300_000 >= now }
                ?: Schedules.next(rule, now, ZoneId.systemDefault())
            cancelRule(rule.id)
            if (next == null) { db.dao().saveMeta(MetaRow("schedule:${rule.id}", "")); continue }
            db.dao().saveMeta(MetaRow("schedule:${rule.id}", "${rule.version}:$next"))
            val i = intent("TIME", rule.id).putExtra("id", rule.id).putExtra("version", rule.version).putExtra("planned", next)
            schedule(next, pending(i))
        }
        for (snooze in db.dao().snoozes()) {
            if (snooze.at + 300_000 < now) { cancelSnooze(snooze.key); db.dao().deleteSnooze(snooze.key) }
            else scheduleSnooze(snooze.key, snooze.at)
        }
    }
    fun scheduleSnooze(key: String, at: Long) {
        schedule(at, pending(intent("SNOOZE", key).putExtra("key", key)))
    }
    fun scheduleFocus(id: Long, at: Long) {
        schedule(at, pending(intent("FOCUS", id.toString()).putExtra("focusId", id)))
    }
    fun installWorker() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("usage-maintenance", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build())
    }
}
