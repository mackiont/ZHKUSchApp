package cn.edu.zhku.schedule

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.zhku.schedule.data.CampusPreset
import cn.edu.zhku.schedule.data.SectionTimes
import cn.edu.zhku.schedule.data.Settings
import cn.edu.zhku.schedule.data.WeekSource
import cn.edu.zhku.schedule.ui.SettingsScreen
import cn.edu.zhku.schedule.ui.theme.ZhkuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页的「添加桌面小组件」入口。
 *
 * 这条路径是小米等桌面在小组件列表里不显示第三方应用时的兜底，
 * 所以既要能滚动到，也要真的把点击传出去。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsWidgetEntryTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun settings() = Settings(
        account = "202620264422",
        hasPassword = true,
        hasCookie = true,
        autoFetch = true,
        notifyEnabled = true,
        sections = SectionTimes.preset(CampusPreset.BAIYUN)
    )

    private fun content(onAddWidget: () -> Unit) {
        rule.setContent {
            ZhkuTheme(dynamicColor = false) {
                SettingsScreen(
                    settings = settings(),
                    currentWeek = 13,
                    weekSource = WeekSource.SERVER,
                    refreshing = false,
                    onRefresh = {},
                    onLogout = {},
                    onAutoFetch = {},
                    onFetchMinute = {},
                    onNotifyEnabled = {},
                    onNotifyLead = {},
                    onCampus = {},
                    onSectionTime = { _, _, _ -> },
                    onRelogin = {},
                    onAddWidget = onAddWidget
                )
            }
        }
    }

    @Test
    fun entryScrollsIntoViewAndFiresCallback() {
        var clicks = 0
        content { clicks++ }

        // 设置页是 LazyColumn，入口在「关于」上面，需要先滚到底部附近
        rule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("添加桌面小组件"))

        rule.onAllNodes(hasText("添加桌面小组件")).onFirst().assertIsDisplayed()
        rule.onAllNodes(hasText("添加") and hasClickAction()).onFirst().performClick()

        assertEquals(1, clicks)
    }
}
