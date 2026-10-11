package cn.shouxin.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun fourTabsWorkWithoutUsagePermission() {
        compose.onNodeWithText("今天的注意力").assertIsDisplayed()
        screenshot("today")
        compose.onNodeWithText("规则", useUnmergedTree = true).performClick()
        compose.onNodeWithText("提醒要有分寸").assertIsDisplayed()
        screenshot("rules")
        compose.onNodeWithText("专注", useUnmergedTree = true).performClick()
        compose.onNodeWithText("现在，做一点想做的事").assertIsDisplayed()
        screenshot("focus")
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("我的节奏").assertIsDisplayed()
        screenshot("profile")
        compose.onNodeWithText("今天", useUnmergedTree = true).performClick()
        compose.onNodeWithText("今天的注意力").assertIsDisplayed()
    }
}
