package cn.shouxin.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.room.withTransaction
import cn.shouxin.app.data.*
import cn.shouxin.app.platform.*
import cn.shouxin.app.update.UpdateManager
import cn.shouxin.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

data class AppChoice(val packageName: String, val name: String)
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = AppDatabase.open(app)
    val dao = db.dao()
    val settings = SettingsStore(app)
    val notifier = Notifier(app)
    val scheduler = ReminderScheduler(app, db)
    val collector = UsageCollector(app, db, settings)
    val focus = FocusManager(app, db, scheduler)
    val updates = UpdateManager(app, db)
    val feedback = MutableStateFlow<String?>(null)
    val apps = MutableStateFlow<List<AppChoice>>(emptyList())
    val guardianUntil = MutableStateFlow(0L)
    private val dispatchMutex = Mutex()
    fun launch(action: suspend () -> Unit) { scope.launch { try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { feedback.value = "操作未完成，请检查权限或稍后重试。原有数据已保留。" } } }
    suspend fun initialize() {
        db.withTransaction {
            if (dao.meta("seeded") == null) {
                val defaults = listOf(
                    Rule("morning", type = RuleType.FIXED_TIME, enabled = false),
                    Rule("hourly", type = RuleType.INTERVAL, enabled = false),
                    Rule("continuous", type = RuleType.CONTINUOUS_GROUP, windowStart = 480, windowEnd = 1350),
                    Rule("daily-budget", type = RuleType.DAILY_BUDGET, thresholdMinutes = 60, windowStart = 480, windowEnd = 1350)
                )
                defaults.forEach { dao.saveRule(RuleRow.of(it)) }; dao.saveMeta(MetaRow("seeded", "1"))
            }
        }
        notifier.channels(); scheduler.installWorker(); scheduler.reconcile()
        finishFocusIfDue(); recoverOutbox(); updates.recoverDownload(); refresh()
    }
    suspend fun refresh() {
        finishFocusIfDue(); collector.refresh(); loadApps()
    }
    suspend fun loadApps() {
        val pm = app.packageManager
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }.toMutableMap()
        val extra = settings.read().targets + collector.dashboard.value.appMillis.keys
        for (pkg in extra) if (pkg !in launcher && pkg != app.packageName) {
            launcher[pkg] = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        apps.value = launcher.filterKeys { it != app.packageName }.map { AppChoice(it.key, it.value) }.sortedBy { it.name }
    }
    suspend fun saveTargets(targets: Set<String>) {
        settings.change { it.copy(targets = targets) }
        db.withTransaction {
            dao.rules().filter { it.type in setOf("CONTINUOUS_GROUP", "DAILY_BUDGET") }.forEach { dao.saveRule(it.copy(version = it.version + 1)) }
            dao.snoozes().forEach { scheduler.cancelSnooze(it.key) }; dao.clearSnoozes()
        }
        scheduler.reconcile(); collector.refresh()
    }
    suspend fun saveRule(rule: Rule) {
        require(rule.validate())
        val old = dao.rule(rule.id)
        dao.saveRule(RuleRow.of(rule.copy(version = (old?.version ?: 0) + 1)))
        scheduler.reconcile()
    }
    suspend fun deleteRule(id: String) {
        scheduler.cancelRule(id); dao.deleteRule(id)
        dao.snoozes().filter { it.ruleId == id }.forEach { scheduler.cancelSnooze(it.key); dao.deleteSnooze(it.key) }
    }
    suspend fun pauseToday() {
        settings.change { it.copy(pausedDate = localDate(System.currentTimeMillis(), ZoneId.systemDefault()).toString()) }
        dao.snoozes().forEach { scheduler.cancelSnooze(it.key) }; dao.clearSnoozes()
        dao.pendingNotifications().filter { it.type != "FOCUS_END" }.forEach { dao.markNotification(it.id, "CANCELLED") }
        feedback.value = "今天的鼓励已暂停，明天自动恢复。专注计时仍可使用。"
    }
    suspend fun evaluate(ruleId: String? = null, version: Int = 0, plannedAt: Long? = null, snoozeKey: String? = null) = dispatchMutex.withLock {
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault(); val s = settings.read()
        val today = localDate(now, zone).toString()
        val rows = dao.history(today, now - 86_400_000L)
        val history = ReminderHistory(rows.map { it.key }.toSet(), rows.size, rows.maxOfOrNull { it.createdAt } ?: 0,
            rows.any { it.key == "budget:$today" })
        val policy = Policy(s.quietStart, s.quietEnd, s.dailyLimit, s.cooldownMinutes * 60_000L, s.pausedDate,
            Permissions.notifications(app), dao.runningFocus() != null, s.monitoring)
        val snooze = snoozeKey?.let { dao.snooze(it) }
        if (snoozeKey != null && snooze == null) return@withLock
        val candidates = dao.rules().mapNotNull { row ->
            val rule = row.model()
            val isTimed = rule.type in setOf(RuleType.FIXED_TIME, RuleType.INTERVAL)
            if (snooze != null && (rule.id != snooze.ruleId || rule.version != snooze.ruleVersion)) return@mapNotNull null
            if (snooze == null && isTimed && (rule.id != ruleId || rule.version != version)) return@mapNotNull null
            val plan = if (snooze != null) snooze.at else plannedAt
            RuleEngine.evaluate(rule, policy, collector.snapshot.copy(guardianRunning = GuardianService.running, available = collector.snapshot.available && collector.snapshotTargets == s.targets), history, now, zone, s.targets, plan, snoozeKey).candidate
        }
        val chosen = candidates.maxByOrNull { it.priority }
        if (snoozeKey != null) dao.deleteSnooze(snoozeKey)
        if (chosen == null) return@withLock
        val recent = rows.sortedByDescending { it.createdAt }.take(10).map { it.contentId }.toSet()
        val content = ContentLibrary.select(s.copy(style = chosen.rule.style.takeIf { it != "INHERIT" } ?: s.style), chosen.rule.type,
            collector.snapshot.groupSession?.durationMillis?.div(60_000)?.toInt(), recent)
        val row = NotificationRow(key = chosen.key, ruleId = chosen.rule.id, ruleVersion = chosen.rule.version,
            createdAt = now, date = today, expiresAt = chosen.expiresAt, state = "PENDING", type = chosen.rule.type.name,
            contentId = content.id, title = content.title, body = content.body,
            snoozeCount = snooze?.let { dao.notificationById(it.originId)?.snoozeCount } ?: 0)
        val id = db.withTransaction {
            if (dao.notification(row.key) != null || dao.history(today, now - 86_400_000L).size >= s.dailyLimit) -1L else dao.insertNotification(row)
        }
        if (id > 0) submit(row.copy(id = id))
    }
    private suspend fun submit(row: NotificationRow) {
        val now = System.currentTimeMillis(); val s = settings.read()
        val focusEnd = row.type == "FOCUS_END"
        val valid = if (focusEnd) true else dao.rule(row.ruleId)?.let { it.enabled && it.version == row.ruleVersion } == true
        val quiet = Policy(s.quietStart, s.quietEnd).quietAt(java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime())
        if (row.expiresAt < now || !valid || quiet || (!focusEnd && (s.pausedDate == localDate(now, ZoneId.systemDefault()).toString() || dao.runningFocus() != null))) {
            dao.markNotification(row.id, "CANCELLED"); return
        }
        try { notifier.submit(row); dao.markNotification(row.id, "SUBMITTED") }
        catch (_: Exception) { dao.markNotification(row.id, "FAILED") }
    }
    suspend fun recoverOutbox() = dispatchMutex.withLock {
        // Stale usage-trigger requests cannot be replayed after process death: their live state is unknown.
        for (row in dao.pendingNotifications()) {
            if (row.type in setOf("CONTINUOUS_GROUP", "DAILY_BUDGET")) dao.markNotification(row.id, "CANCELLED") else submit(row)
        }
    }
    suspend fun snooze(id: Long) = dispatchMutex.withLock {
        val origin = dao.notificationById(id) ?: return@withLock
        val rule = dao.rule(origin.ruleId) ?: return@withLock
        if (dao.snoozes().any { it.originId == id }) return@withLock
        if (origin.snoozeCount >= 2 || rule.version != origin.ruleVersion || !rule.enabled) return@withLock
        val key = "snooze:${origin.id}:${origin.snoozeCount + 1}"
        val at = System.currentTimeMillis() + 600_000
        db.withTransaction { dao.saveSnooze(SnoozeRow(key, origin.id, rule.id, rule.version, at)); dao.incrementSnooze(id) }
        scheduler.scheduleSnooze(key, at); notifier.cancel(id)
    }
    suspend fun finishFocusIfDue() {
        val done = focus.recover() ?: return
        val now = System.currentTimeMillis()
        val row = NotificationRow(key = "focus:${done.id}:end", ruleId = "", ruleVersion = 0, createdAt = now,
            date = localDate(now, ZoneId.systemDefault()).toString(), expiresAt = done.startedAt + done.durationMillis + 300_000,
            state = "PENDING", type = "FOCUS_END", contentId = "focus-end", title = "这一段专注计时完成了", body = "给自己一个短休息，再决定要不要继续。")
        dispatchMutex.withLock { val id = dao.insertNotification(row); if (id > 0) submit(row.copy(id = id)) }
    }
    suspend fun clearRecords() {
        app.stopService(Intent(app, GuardianService::class.java))
        dao.runningFocus()?.let { scheduler.cancelFocus(it.id) }
        dao.snoozes().forEach { scheduler.cancelSnooze(it.key) }
        collector.exclusiveClear {
            val marker = System.currentTimeMillis()
            settings.change { it.copy(usageStartAt = marker) }
            dispatchMutex.withLock {
                db.withTransaction {
                    dao.clearEvents(); dao.clearDaily(); dao.clearNotifications(); dao.clearFocus(); dao.clearSnoozes()
                    dao.saveMeta(MetaRow("last_query", marker.toString()))
                }
            }
        }
        collector.clearMemory(); notifier.cancelAll(); guardianUntil.value = 0; scheduler.reconcile()
    }
    suspend fun exportData(): String {
        val s = settings.read()
        fun array(rows: List<JSONObject>) = JSONArray(rows)
        return db.withTransaction {
            JSONObject().put("schemaVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
                .put("exportedAt", System.currentTimeMillis()).put("timezone", ZoneId.systemDefault().id)
                .put("retention", JSONObject().put("usageEventsDays", 7).put("summaryAndHistoryDays", 90))
                .put("settings", JSONObject().put("targets", JSONArray(s.targets.sorted())).put("goal", s.goal)
                    .put("style", s.style).put("quietStart", s.quietStart).put("quietEnd", s.quietEnd)
                    .put("dailyLimit", s.dailyLimit).put("cooldownMinutes", s.cooldownMinutes)
                    .put("pausedDate", s.pausedDate).put("monitoring", s.monitoring).put("usageStartAt", s.usageStartAt)
                    .put("customPhrases", JSONArray(s.customPhrases)).put("favorites", JSONArray(s.favorites.sorted()))
                    .put("blocked", JSONArray(s.blocked.sorted())).put("repository", s.repository))
                .put("rules", array(dao.rules().map { r -> JSONObject().put("id", r.id).put("version", r.version)
                    .put("type", r.type).put("enabled", r.enabled).put("hour", r.hour).put("minute", r.minute)
                    .put("intervalMinutes", r.intervalMinutes).put("thresholdMinutes", r.thresholdMinutes)
                    .put("windowStart", r.windowStart).put("windowEnd", r.windowEnd).put("weekdays", r.weekdays).put("style", r.style) }))
                .put("availableUsageEvents", array(dao.events(0).map { r -> JSONObject().put("at", r.at).put("package", r.packageName).put("kind", r.kind).put("activityIdentity", r.instanceId) }))
                .put("dailyUsage", array(dao.dailyExport().map { r -> JSONObject().put("date", r.date).put("package", r.packageName).put("millis", r.millis).put("updatedAt", r.updatedAt).put("quality", r.quality) }))
                .put("reminders", array(dao.notificationsExport().map { r -> JSONObject().put("id", r.id).put("key", r.key)
                    .put("ruleId", r.ruleId).put("ruleVersion", r.ruleVersion).put("createdAt", r.createdAt)
                    .put("state", r.state).put("type", r.type).put("contentId", r.contentId).put("title", r.title)
                    .put("body", r.body).put("snoozeCount", r.snoozeCount) }))
                .put("focusSessions", array(dao.focusExport().map { r -> JSONObject().put("id", r.id).put("task", r.task)
                    .put("startedAt", r.startedAt).put("durationMillis", r.durationMillis).put("actualMillis", r.actualMillis)
                    .put("state", r.state).put("note", r.note) })).toString(2)
        }
    }
}
