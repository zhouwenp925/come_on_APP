package cn.shouxin.core

import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    private val base = 1_000_000L
    private fun e(seconds: Long, pkg: String, kind: EventKind = EventKind.RESUME, instance: Int = 0) = UsageEvent(base + seconds * 1000, pkg, kind, instance)
    @Test fun switchingWithinTargetGroupMaintainsSession() {
        val result = UsageReconstructor.replay(listOf(e(0, "video"), e(480, "social")), base + 960_000)
        assertEquals(960_000L, result.groupSession(setOf("video", "social"), base + 960_000)!!.durationMillis)
        assertEquals(960_000L, result.duration(base, base + 960_000))
    }
    @Test fun shortAwayTimeDoesNotCountAsUsage() {
        val result = UsageReconstructor.replay(listOf(e(0, "video"), e(480, "work"), e(510, "video")), base + 990_000)
        val session = result.groupSession(setOf("video"), base + 990_000)!!
        assertEquals(base, session.startedAt); assertEquals(960_000L, session.durationMillis)
    }
    @Test fun sixtySecondsAwayCreatesNewSession() {
        val result = UsageReconstructor.replay(listOf(e(0, "video"), e(480, "work"), e(540, "video")), base + 1_020_000)
        assertEquals(base + 540_000, result.groupSession(setOf("video"), base + 1_020_000)!!.startedAt)
    }
    @Test fun lockAlwaysResetsEvenWhenShort() {
        val result = UsageReconstructor.replay(listOf(e(0, "video"), e(480, "", EventKind.LOCK), e(490, "", EventKind.UNLOCK), e(490, "video")), base + 970_000)
        assertEquals(480_000L, result.groupSession(setOf("video"), base + 970_000)!!.durationMillis)
    }
    @Test fun pauseOfOldActivityDoesNotCloseNewActivity() {
        val result = UsageReconstructor.replay(listOf(e(0, "video", instance = 1), e(10, "video", instance = 2), e(11, "video", EventKind.PAUSE, 1)), base + 20_000)
        assertEquals(20_000L, result.duration(base, base + 20_000)); assertEquals("video", result.activePackage)
    }
    @Test fun crossingDateBoundaryClipsToRequestedWindow() {
        val result = UsageReconstructor.replay(listOf(e(0, "video")), base + 1_200_000)
        assertEquals(600_000L, result.duration(base + 600_000, base + 1_200_000))
    }
    @Test fun overlappingIntervalsAreCountedOnce() { assertEquals(30L, unionDuration(listOf(0L to 20L, 10L to 30L, 5L to 5L))) }
    @Test fun missingEventsDoNotInventActivePackage() { assertNull(UsageReconstructor.replay(emptyList(), base).activePackage) }
    @Test fun rebootDoesNotCountPoweredOffTimeOrRestoreOldApp() {
        val result = UsageReconstructor.replay(listOf(e(0, "video"), e(60, "", EventKind.DEVICE_SHUTDOWN),
            e(600, "", EventKind.DEVICE_STARTUP), e(610, "", EventKind.UNLOCK), e(620, "video")), base + 650_000)
        assertEquals(90_000L, result.duration(base, base + 650_000))
        assertEquals(base + 620_000, result.groupSession(setOf("video"), base + 650_000)!!.startedAt)
    }
}
