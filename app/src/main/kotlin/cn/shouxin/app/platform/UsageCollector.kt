package cn.shouxin.app.platform

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.UserManager
import androidx.room.withTransaction
import cn.shouxin.app.data.*
import cn.shouxin.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

data class UsageDashboard(
    val available: Boolean = false, val phoneMillis: Long = 0, val groupMillis: Long = 0,
    val updatedAt: Long = 0, val quality: String = "尚未获得使用数据", val appMillis: Map<String, Long> = emptyMap()
)
class UsageCollector(private val context: Context, private val db: AppDatabase, private val settings: SettingsStore) {
    val dashboard = MutableStateFlow(UsageDashboard())
    var snapshot = UsageSnapshot(false); private set
    private val mutex = Mutex()
    var snapshotTargets: Set<String> = emptySet(); private set
    suspend fun exclusiveClear(action: suspend () -> Unit) = mutex.withLock { action(); clearMemory() }
    suspend fun refresh(): UsageSnapshot = mutex.withLock {
        val s = settings.read(); val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        if (!s.monitoring || !Permissions.usage(context) || !context.getSystemService(UserManager::class.java).isUserUnlocked) {
            snapshot = UsageSnapshot(false)
            dashboard.value = dashboard.value.copy(available = false, quality = if (!s.monitoring) "监测已关闭" else "使用访问未授权或暂不可用")
            return@withLock snapshot
        }
        try {
            val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val historyStart = maxOf(day.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(), s.usageStartAt)
            val lastQuery = db.dao().meta("last_query")?.toLongOrNull() ?: 0L
            val begin = maxOf(historyStart, (lastQuery - 60_000).coerceAtMost(now))
            val query = context.getSystemService(UsageStatsManager::class.java).queryEvents(begin, now)
                ?: error("USAGE_UNAVAILABLE")
            val rows = mutableListOf<EventRow>(); val event = UsageEvents.Event()
            while (query.hasNextEvent()) {
                query.getNextEvent(event)
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> EventKind.RESUME
                    UsageEvents.Event.ACTIVITY_PAUSED -> EventKind.PAUSE
                    UsageEvents.Event.SCREEN_INTERACTIVE -> EventKind.SCREEN_ON
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventKind.SCREEN_OFF
                    UsageEvents.Event.KEYGUARD_SHOWN -> EventKind.LOCK
                    UsageEvents.Event.KEYGUARD_HIDDEN -> EventKind.UNLOCK
                    UsageEvents.Event.DEVICE_SHUTDOWN -> EventKind.DEVICE_SHUTDOWN
                    UsageEvents.Event.DEVICE_STARTUP -> EventKind.DEVICE_STARTUP
                    else -> null
                } ?: continue
                val pkg = event.packageName ?: ""
                // The public SDK exposes className, not the hidden activity instance identifier.
                val activityIdentity = event.className?.hashCode() ?: 0
                val key = "${event.timeStamp}:$pkg:${kind.name}:${event.className.orEmpty()}"
                rows += EventRow(key, event.timeStamp, pkg, kind.name, activityIdentity)
            }
            db.dao().insertEvents(rows)
            val events = db.dao().events(historyStart).map { UsageEvent(it.at, it.packageName, EventKind.valueOf(it.kind), it.instanceId) }
            if (events.isEmpty()) {
                snapshot = UsageSnapshot(false)
                dashboard.value = dashboard.value.copy(available = false, quality = "未获得前台事件，请稍后刷新；缺失数据未记为零")
                return@withLock snapshot
            }
            val replay = UsageReconstructor.replay(events, now)
            if (settings.read().targets != s.targets || settings.read().usageStartAt != s.usageStartAt) {
                snapshot = UsageSnapshot(false); return@withLock snapshot
            }
            val todayStart = maxOf(day.atStartOfDay(zone).toInstant().toEpochMilli(), s.usageStartAt)
            val currentKnown = context.getSystemService(PowerManager::class.java).isInteractive && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val active = replay.activePackage.takeIf { currentKnown }
            val appTimes = replay.intervals.map { it.packageName }.distinct().associateWith { replay.duration(todayStart, now, setOf(it)) }.filterValues { it > 0 }
            val quality = if (historyStart > day.atStartOfDay(zone).toInstant().toEpochMilli() || events.isEmpty()) "部分覆盖 / 估算" else "前台事件估算"
            snapshot = UsageSnapshot(true, now, replay.duration(todayStart, now, s.targets), active,
                replay.groupSession(s.targets, now), GuardianService.running, quality)
            snapshotTargets = s.targets
            dashboard.value = UsageDashboard(true, replay.duration(todayStart, now), snapshot.dailyGroupMillis, now, quality, appTimes)
            db.withTransaction {
                for (offset in 0L..2L) {
                    val date = day.minusDays(offset); val from = maxOf(date.atStartOfDay(zone).toInstant().toEpochMilli(), historyStart)
                    val to = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
                    if (to <= from) continue
                    val packages = replay.intervals.map { it.packageName }.distinct()
                    if (events.none { it.at < to }) continue // No known state in this day: retain missing data.
                    val summary = packages.map { DailyRow(date.toString(), it, replay.duration(from, to, setOf(it)), now, quality) }.filter { it.millis > 0 }
                    db.dao().deleteDay(date.toString()); db.dao().saveDaily(summary + DailyRow(date.toString(), "@phone", replay.duration(from, to), now, quality))
                }
                db.dao().saveMeta(MetaRow("last_query", now.toString()))
                db.dao().pruneEvents(now - 7 * 86_400_000L)
                db.dao().pruneDaily(day.minusDays(90).toString())
                db.dao().pruneNotifications(now - 90 * 86_400_000L)
                db.dao().pruneFocus(now - 90 * 86_400_000L)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            snapshot = UsageSnapshot(false)
            dashboard.value = dashboard.value.copy(available = false, quality = "采集暂不可用，未将缺失数据记为零")
        }
        snapshot
    }
    fun clearMemory() { snapshot = UsageSnapshot(false); dashboard.value = UsageDashboard() }
}
