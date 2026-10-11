package cn.shouxin.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/shouxin-tests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val image = instrumentation.uiAutomation.takeScreenshot()
        checkNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
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
