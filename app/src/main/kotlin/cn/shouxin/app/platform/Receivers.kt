package cn.shouxin.app.platform

import android.content.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.shouxin.app.graph

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "cn.shouxin.STOP_GUARD") { context.stopService(Intent(context, GuardianService::class.java)); return }
        val pending = goAsync()
        context.graph.launch {
            try {
                val graph = context.graph
                when (intent.action) {
                    "cn.shouxin.PAUSE_TODAY" -> graph.pauseToday()
                    "cn.shouxin.SNOOZE_ACTION" -> graph.snooze(intent.getLongExtra("notificationId", -1))
                    "cn.shouxin.TIME" -> {
                        val id = intent.getStringExtra("id"); val version = intent.getIntExtra("version", 0); val planned = intent.getLongExtra("planned", 0)
                        graph.collector.refresh(); graph.evaluate(id, version, planned)
                        graph.scheduler.markTimeHandled(id, version, planned); graph.scheduler.reconcile()
                    }
                    "cn.shouxin.SNOOZE" -> { graph.collector.refresh(); graph.evaluate(snoozeKey = intent.getStringExtra("key")); graph.scheduler.reconcile() }
                    "cn.shouxin.FOCUS" -> graph.finishFocusIfDue()
                }
            } finally { pending.finish() }
        }
    }
}
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        context.graph.launch {
            try {
                if (intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) context.graph.scheduler.invalidateTimePlans()
                context.graph.scheduler.installWorker(); context.graph.scheduler.reconcile(); context.graph.finishFocusIfDue()
            }
            finally { pending.finish() }
        }
    }
}
class MaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        applicationContext.graph.finishFocusIfDue(); applicationContext.graph.collector.refresh()
        applicationContext.graph.evaluate(); applicationContext.graph.scheduler.reconcile(); Result.success()
    } catch (_: Exception) { Result.retry() }
}
