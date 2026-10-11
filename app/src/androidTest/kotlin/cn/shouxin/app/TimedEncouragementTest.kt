package cn.shouxin.app

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shouxin.app.platform.Permissions
import cn.shouxin.core.Rule as ReminderRule
import cn.shouxin.core.RuleType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.time.ZonedDateTime

/** Exercise an actual system alarm -> receiver -> rule engine -> notification, not a manual broadcast. */
@RunWith(AndroidJUnit4::class)
class TimedEncouragementTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
    @Test fun scheduledQuoteArrivesWithoutUsageAccessOrGuardian() = runBlocking {
        val activity = compose.activity
        val graph = activity.graph
        val pkg = activity.packageName
        val previous = graph.settings.read()
        val id = "scheduled-quote-test"
        try {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            shell("appops set $pkg GET_USAGE_STATS deny")
            assertFalse(Permissions.usage(activity))
            // On Android 14, granting special access does not kill the instrumentation process.
            // Revoking it would; leave permission cleanup to the disposable CI emulator.
            if (Build.VERSION.SDK_INT >= 31) {
                if (!Permissions.exactAlarms(activity)) {
                    val fallbackAt = ZonedDateTime.now().plusMinutes(2)
                    graph.saveRule(ReminderRule(id, type = RuleType.FIXED_TIME, hour = fallbackAt.hour, minute = fallbackAt.minute))
                }
                shell("appops set $pkg SCHEDULE_EXACT_ALARM allow")
            }
            assertTrue(Permissions.exactAlarms(activity))
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            graph.notifier.channels()
            graph.settings.change { it.copy(style = "INSPIRING", monitoring = false, dailyLimit = 20, cooldownMinutes = 5,
                pausedDate = "", customPhrases = emptyList(), favorites = emptySet(), blocked = emptySet()) }
            val at = ZonedDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
            val quietStart = (at.hour * 60 + at.minute + 720) % 1440
            graph.settings.change { it.copy(quietStart = quietStart, quietEnd = (quietStart + 1) % 1440) }
            graph.saveRule(ReminderRule(id, type = RuleType.FIXED_TIME, hour = at.hour, minute = at.minute, style = "INSPIRING"))
            val deadline = SystemClock.elapsedRealtime() + 75_000
            var sent = graph.dao.notificationsExport().firstOrNull { it.ruleId == id && it.state == "SUBMITTED" }
            while (sent == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
                sent = graph.dao.notificationsExport().firstOrNull { it.ruleId == id && it.state == "SUBMITTED" }
            }
            assertNotNull("System alarm did not submit the scheduled encouragement", sent)
            val row = checkNotNull(sent)
            val delivered = activity.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.id == (row.id + 100).toInt() }
            assertNotNull("No actual system notification for scheduled quote", delivered)
            assertEquals(row.body, delivered?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertTrue(row.createdAt >= at.toInstant().toEpochMilli())
        } finally {
            graph.deleteRule(id)
            graph.settings.change { previous }
            graph.notifier.cancelAll()
            shell("appops set $pkg GET_USAGE_STATS default")
        }
    }
}
