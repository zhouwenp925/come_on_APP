package cn.shouxin.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val history = ReminderHistory(emptySet(), 0, 0, false)
    private val rule = Rule("continuous", type = RuleType.CONTINUOUS_GROUP)
    private val usage = UsageSnapshot(true, now, 3_600_000, "video", GroupSession(now - 900_000, 900_000), true)
    private fun decide(policy: Policy = Policy(), snapshot: UsageSnapshot = usage, h: ReminderHistory = history, r: Rule = rule, at: Long = now, planned: Long? = null, snooze: String? = null) =
        RuleEngine.evaluate(r, policy, snapshot, h, at, zone, setOf("video", "social"), planned, snooze)
    @Test fun continuousThresholdProducesOneStableKey() {
        val first = decide().candidate!!
        assertEquals("DUPLICATE", decide(h = history.copy(keys = setOf(first.key))).reason)
    }
    @Test fun missingDataNeverTriggers() { assertEquals("USAGE_UNAVAILABLE", decide(snapshot = usage.copy(available = false)).reason) }
    @Test fun staleQueryNeverTriggers() { assertEquals("USAGE_UNAVAILABLE", decide(snapshot = usage.copy(queriedAt = now - 36_000)).reason) }
    @Test fun stoppedGuardianNeverClaimsRealtime() { assertEquals("GUARDIAN_STOPPED", decide(snapshot = usage.copy(guardianRunning = false)).reason) }
    @Test fun leavingGroupSuppressesPendingContinuousAlert() { assertEquals("LEFT_GROUP", decide(snapshot = usage.copy(activePackage = "work")).reason) }
    @Test fun quietHoursCrossMidnight() {
        assertTrue(Policy().quietAt(LocalTime.of(23, 0))); assertTrue(Policy().quietAt(LocalTime.of(7, 59)))
        assertFalse(Policy().quietAt(LocalTime.of(8, 0))); assertFalse(Policy().quietAt(LocalTime.of(22, 29)))
    }
    @Test fun focusSuppressesMotivation() { assertEquals("FOCUS_RUNNING", decide(policy = Policy(focusRunning = true)).reason) }
    @Test fun dailyLimitCannotBeBypassedBySnooze() { assertEquals("DAILY_LIMIT", decide(h = history.copy(dailyCount = 6), snooze = "explicit").reason) }
    @Test fun ordinaryCooldownButExplicitSnoozeMayProceed() {
        val recent = history.copy(lastAt = now - 60_000)
        assertEquals("COOLDOWN", decide(h = recent).reason)
        assertNotNull(decide(h = recent, snooze = "explicit").candidate)
    }
    @Test fun budgetRemainsDeduplicatedAfterVersionChanges() {
        val budget = rule.copy(type = RuleType.DAILY_BUDGET, version = 7, thresholdMinutes = 60)
        assertEquals("ALREADY_ALERTED", decide(r = budget, h = history.copy(budgetAlreadyAlerted = true)).reason)
        assertNotNull(decide(r = budget, h = history.copy(budgetAlreadyAlerted = true), snooze = "explicit").candidate)
    }
    @Test fun fixedTimeDoesNotDependOnUsagePermission() {
        assertNotNull(decide(r = rule.copy(type = RuleType.FIXED_TIME), snapshot = UsageSnapshot(false), planned = now).candidate)
    }
    @Test fun delayedFixedTimeExpiresInsteadOfBursting() { assertEquals("EXPIRED", decide(r = rule.copy(type = RuleType.FIXED_TIME), planned = now - 300_001).reason) }
    @Test fun clockRollbackCannotGrantFreshCooldown() { assertEquals("COOLDOWN", decide(h = history.copy(lastAt = now + 1000)).reason) }
    @Test fun calendarIntervalsDoNotDrift() {
        val r = rule.copy(type = RuleType.INTERVAL)
        assertEquals(now + 3_600_000, Schedules.next(r, now + 150_000, zone))
    }
    @Test fun weeklyScheduleSkipsUnselectedDays() {
        val r = rule.copy(type = RuleType.FIXED_TIME, weekdays = setOf(2), hour = 9)
        val expected = ZonedDateTime.of(2026, 10, 13, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, Schedules.next(r, now, zone))
    }
    @Test fun dstMissingTimeIsSkipped() {
        val ny = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny).toInstant().toEpochMilli()
        val r = rule.copy(type = RuleType.FIXED_TIME, hour = 2, minute = 30)
        assertEquals(LocalDate.of(2026, 3, 9), localDate(Schedules.next(r, before, ny)!!, ny))
    }
    @Test fun dstRepeatedHourExecutesOnlyFirstOccurrence() {
        val ny = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny).toInstant().toEpochMilli()
        val r = rule.copy(type = RuleType.FIXED_TIME, hour = 1, minute = 30)
        val first = Schedules.next(r, before, ny)!!
        assertEquals(LocalDate.of(2026, 11, 2), localDate(Schedules.next(r, first + 1, ny)!!, ny))
    }
}
