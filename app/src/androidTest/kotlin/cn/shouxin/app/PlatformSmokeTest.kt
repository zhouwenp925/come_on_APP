package cn.shouxin.app

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shouxin.app.platform.GuardianService
import cn.shouxin.app.platform.Permissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class PlatformSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        descriptor.use { FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
    }
    private fun awaitState(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue("Platform state did not become ready", condition())
    }
    @Test fun permissionsUsageNotificationAndForegroundGuardianWork() = runBlocking {
        val activity = compose.activity
        val graph = activity.graph
        val previous = graph.settings.read()
        val pkg = activity.packageName
        graph.notifier.channels()
        try {
            shell("appops set $pkg GET_USAGE_STATS deny")
            assertFalse(Permissions.usage(activity))
            assertFalse(graph.collector.refresh().available)
            shell("appops set $pkg GET_USAGE_STATS allow")
            assertTrue(Permissions.usage(activity))
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            assertTrue(Permissions.notifications(activity))
            graph.notifier.preview()
            val manager = activity.getSystemService(NotificationManager::class.java)
            awaitState { manager.activeNotifications.any { it.id == 42 } }

            graph.settings.change { it.copy(monitoring = true, targets = setOf("com.android.settings")) }
            compose.runOnUiThread { ContextCompat.startForegroundService(activity,
                Intent(activity, GuardianService::class.java).putExtra("minutes", 1)) }
            awaitState { GuardianService.running }
            awaitState { manager.activeNotifications.any { it.id == 1 } }
            compose.runOnUiThread { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            SystemClock.sleep(2000) // Generate a real platform foreground interval, not a mock snapshot.
            val snapshot = graph.collector.refresh()
            assertTrue(snapshot.available)
            assertEquals("com.android.settings", snapshot.activePackage)
            assertTrue("Settings foreground usage was not collected", snapshot.dailyGroupMillis > 0)

            activity.stopService(Intent(activity, GuardianService::class.java))
            awaitState { !GuardianService.running }
            shell("appops set $pkg GET_USAGE_STATS deny")
            assertFalse(graph.collector.refresh().available)
        } finally {
            activity.stopService(Intent(activity, GuardianService::class.java))
            graph.settings.change { previous }
            graph.notifier.cancelAll()
            shell("appops set $pkg GET_USAGE_STATS default")
            if (Build.VERSION.SDK_INT >= 33) shell("pm revoke $pkg android.permission.POST_NOTIFICATIONS")
        }
    }
}
