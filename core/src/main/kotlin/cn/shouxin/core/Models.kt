package cn.shouxin.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

enum class RuleType { FIXED_TIME, INTERVAL, CONTINUOUS_GROUP, DAILY_BUDGET }
data class Rule(
    val id: String, val version: Int = 1, val type: RuleType, val enabled: Boolean = true,
    val hour: Int = 9, val minute: Int = 0, val intervalMinutes: Int = 60,
    val thresholdMinutes: Int = 15, val windowStart: Int = 540, val windowEnd: Int = 1260,
    val weekdays: Set<Int> = (1..7).toSet(), val style: String = "INHERIT"
) {
    fun validate(): Boolean = id.isNotBlank() && version > 0 && hour in 0..23 && minute in 0..59 &&
        intervalMinutes in 15..180 && thresholdMinutes in 1..480 &&
        windowStart in 0..1439 && windowEnd in 1..1440 && windowStart < windowEnd &&
        weekdays.isNotEmpty() && weekdays.all { it in 1..7 }
}
data class Policy(
    val quietStart: Int = 1350, val quietEnd: Int = 480, val dailyLimit: Int = 6,
    val cooldownMillis: Long = 1_200_000, val pausedDate: String = "", val notificationsEnabled: Boolean = true,
    val focusRunning: Boolean = false, val monitoringEnabled: Boolean = true
) {
    fun quietAt(time: LocalTime): Boolean {
        val minute = time.hour * 60 + time.minute
        return if (quietStart < quietEnd) minute in quietStart until quietEnd
        else minute >= quietStart || minute < quietEnd
    }
}
data class UsageSnapshot(
    val available: Boolean, val queriedAt: Long = 0, val dailyGroupMillis: Long = 0,
    val activePackage: String? = null, val groupSession: GroupSession? = null,
    val guardianRunning: Boolean = false, val quality: String = "PARTIAL"
)
data class ReminderHistory(val keys: Set<String>, val dailyCount: Int, val lastAt: Long, val budgetAlreadyAlerted: Boolean)
data class Candidate(val key: String, val rule: Rule, val plannedAt: Long, val expiresAt: Long, val priority: Int)
data class Decision(val candidate: Candidate? = null, val reason: String = "READY")
fun localDate(timestamp: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
