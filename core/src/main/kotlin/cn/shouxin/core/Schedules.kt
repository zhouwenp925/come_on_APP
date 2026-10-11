package cn.shouxin.core

import java.time.*

object Schedules {
    /** Calendar anchored: delayed callbacks do not shift the next occurrence. DST gaps are skipped. */
    fun next(rule: Rule, after: Long, zone: ZoneId): Long? {
        if (!rule.enabled || !rule.validate() || rule.type !in setOf(RuleType.FIXED_TIME, RuleType.INTERVAL)) return null
        val base = localDate(after, zone)
        for (offset in 0L..8L) {
            val date = base.plusDays(offset)
            if (date.dayOfWeek.value !in rule.weekdays) continue
            val minutes = if (rule.type == RuleType.FIXED_TIME) listOf(rule.hour * 60 + rule.minute)
                else generateSequence(rule.windowStart + rule.intervalMinutes) { it + rule.intervalMinutes }.takeWhile { it < rule.windowEnd }.toList()
            for (minute in minutes) {
                val local = date.atStartOfDay().plusMinutes(minute.toLong())
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.isEmpty()) continue
                val at = local.atOffset(offsets.first()).toInstant().toEpochMilli()
                if (at > after) return at
            }
        }
        return null
    }
}

object RuleEngine {
    fun evaluate(
        rule: Rule, policy: Policy, usage: UsageSnapshot, history: ReminderHistory,
        now: Long, zone: ZoneId, targets: Set<String>, plannedAt: Long? = null,
        snoozeKey: String? = null
    ): Decision {
        fun suppress(reason: String) = Decision(reason = reason)
        if (!rule.validate() || !rule.enabled) return suppress("DISABLED")
        if (!policy.notificationsEnabled) return suppress("NOTIFICATION_PERMISSION")
        val dateTime = Instant.ofEpochMilli(now).atZone(zone)
        if (policy.pausedDate == dateTime.toLocalDate().toString()) return suppress("PAUSED_TODAY")
        if (policy.quietAt(dateTime.toLocalTime())) return suppress("QUIET_HOURS")
        if (policy.focusRunning) return suppress("FOCUS_RUNNING")
        if (dateTime.dayOfWeek.value !in rule.weekdays) return suppress("OUTSIDE_DAYS")
        if (rule.type != RuleType.FIXED_TIME) {
            val minutes = dateTime.hour * 60 + dateTime.minute
            if (minutes !in rule.windowStart until rule.windowEnd) return suppress("OUTSIDE_WINDOW")
        }
        if (history.dailyCount >= policy.dailyLimit) return suppress("DAILY_LIMIT")
        if (snoozeKey == null && (now < history.lastAt || now - history.lastAt < policy.cooldownMillis)) return suppress("COOLDOWN")
        val date = dateTime.toLocalDate().toString()
        val key: String
        val expires: Long
        val priority: Int
        when (rule.type) {
            RuleType.FIXED_TIME, RuleType.INTERVAL -> {
                val planned = plannedAt ?: return suppress("NO_SCHEDULE")
                if (now < planned || now - planned > 300_000) return suppress("EXPIRED")
                key = "${rule.id}:${rule.version}:$planned"
                expires = planned + 300_000
                priority = if (rule.type == RuleType.FIXED_TIME) 20 else 10
            }
            RuleType.CONTINUOUS_GROUP -> {
                if (!policy.monitoringEnabled || !usage.available || now - usage.queriedAt !in 0..35_000) return suppress("USAGE_UNAVAILABLE")
                if (!usage.guardianRunning) return suppress("GUARDIAN_STOPPED")
                if (usage.activePackage !in targets) return suppress("LEFT_GROUP")
                val session = usage.groupSession ?: return suppress("NO_SESSION")
                if (session.durationMillis < rule.thresholdMinutes * 60_000L) return suppress("BELOW_THRESHOLD")
                key = "${rule.id}:${rule.version}:session:${session.startedAt}"
                expires = now + 35_000
                priority = 30
            }
            RuleType.DAILY_BUDGET -> {
                if (!policy.monitoringEnabled || !usage.available || now - usage.queriedAt !in 0..35_000) return suppress("USAGE_UNAVAILABLE")
                if (history.budgetAlreadyAlerted && snoozeKey == null) return suppress("ALREADY_ALERTED")
                if (usage.dailyGroupMillis < rule.thresholdMinutes * 60_000L) return suppress("BELOW_THRESHOLD")
                if (snoozeKey != null && usage.activePackage !in targets) return suppress("LEFT_GROUP")
                key = "budget:$date"
                expires = now + 35_000
                priority = 40
            }
        }
        val finalKey = snoozeKey ?: key
        if (finalKey in history.keys) return suppress("DUPLICATE")
        return Decision(Candidate(finalKey, rule, plannedAt ?: now, expires, if (snoozeKey != null) 50 else priority))
    }
}
