package cn.shouxin.core

enum class EventKind { RESUME, PAUSE, SCREEN_ON, SCREEN_OFF, LOCK, UNLOCK, DEVICE_SHUTDOWN, DEVICE_STARTUP }
data class UsageEvent(val at: Long, val packageName: String = "", val kind: EventKind, val instanceId: Int = 0)
data class UsageInterval(val packageName: String, val start: Long, val end: Long)
data class GroupSession(val startedAt: Long, val durationMillis: Long)
data class UsageResult(val intervals: List<UsageInterval>, val activePackage: String?, val resetTimes: List<Long>) {
    fun duration(start: Long, end: Long, packages: Set<String>? = null): Long = unionDuration(
        intervals.filter { packages == null || it.packageName in packages }.map { maxOf(start, it.start) to minOf(end, it.end) }
    )
    fun groupSession(targets: Set<String>, now: Long, awayResetMillis: Long = 60_000): GroupSession? {
        val selected = intervals.filter { it.packageName in targets && it.end > it.start }.sortedBy { it.start }
        var started = 0L; var lastEnd = 0L; var duration = 0L
        for (interval in selected) {
            val reset = started == 0L || interval.start - lastEnd >= awayResetMillis || resetTimes.any { it in lastEnd..interval.start }
            if (reset) { started = interval.start; duration = 0 }
            duration += maxOf(0L, interval.end - maxOf(lastEnd.takeIf { !reset } ?: interval.start, interval.start))
            lastEnd = maxOf(if (reset) interval.start else lastEnd, interval.end)
        }
        if (started == 0L || now - lastEnd >= awayResetMillis || resetTimes.any { it in lastEnd..now }) return null
        return GroupSession(started, duration)
    }
}
fun unionDuration(intervals: List<Pair<Long, Long>>): Long {
    val sorted = intervals.filter { it.second > it.first }.sortedBy { it.first }
    if (sorted.isEmpty()) return 0
    var start = sorted.first().first; var end = sorted.first().second; var total = 0L
    for ((nextStart, nextEnd) in sorted.drop(1)) {
        if (nextStart <= end) end = maxOf(end, nextEnd)
        else { total += end - start; start = nextStart; end = nextEnd }
    }
    return total + end - start
}
object UsageReconstructor {
    fun replay(events: List<UsageEvent>, until: Long): UsageResult {
        val intervals = mutableListOf<UsageInterval>(); val resets = mutableListOf<Long>()
        var active: String? = null; var instance = 0; var cursor = events.firstOrNull()?.at ?: until
        var interactive = true; var locked = false
        fun append(end: Long) {
            val pkg = active
            if (pkg != null && interactive && !locked && end > cursor) intervals += UsageInterval(pkg, cursor, end)
            cursor = end
        }
        for (event in events.sortedBy { it.at }.filter { it.at <= until }) {
            append(event.at)
            when (event.kind) {
                EventKind.RESUME -> { active = event.packageName; instance = event.instanceId }
                EventKind.PAUSE -> if (active == event.packageName && (instance == event.instanceId || event.instanceId == 0)) active = null
                EventKind.SCREEN_OFF -> { interactive = false; active = null; resets += event.at }
                EventKind.SCREEN_ON -> interactive = true
                EventKind.LOCK -> { locked = true; active = null; resets += event.at }
                EventKind.UNLOCK -> locked = false
                EventKind.DEVICE_SHUTDOWN -> { interactive = false; active = null; resets += event.at }
                EventKind.DEVICE_STARTUP -> { interactive = true; locked = true; active = null; resets += event.at }
            }
        }
        append(until)
        return UsageResult(intervals, active.takeIf { interactive && !locked }, resets)
    }
}
