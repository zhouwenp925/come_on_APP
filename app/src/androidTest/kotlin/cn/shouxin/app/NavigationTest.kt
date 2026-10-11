package cn.shouxin.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun fourTabsWorkWithoutUsagePermission() {
        compose.onNodeWithText("今天的注意力").assertIsDisplayed()
        compose.onNodeWithText("规则", useUnmergedTree = true).performClick()
        compose.onNodeWithText("提醒要有分寸").assertIsDisplayed()
        compose.onNodeWithText("专注", useUnmergedTree = true).performClick()
        compose.onNodeWithText("现在，做一点想做的事").assertIsDisplayed()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("我的节奏").assertIsDisplayed()
        compose.onNodeWithText("今天", useUnmergedTree = true).performClick()
        compose.onNodeWithText("今天的注意力").assertIsDisplayed()
    }
}
