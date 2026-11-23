package cn.edu.zhku.schedule

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.zhku.schedule.data.Course
import cn.edu.zhku.schedule.data.SectionTimes
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.ui.HomeScreen
import cn.edu.zhku.schedule.ui.WeekScreen
import cn.edu.zhku.schedule.ui.theme.ZhkuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 用 Robolectric 在 JVM 上把界面渲染成 PNG，方便检查排版（不需要真机/模拟器）。
 * 产物在 app/build/screenshots 目录。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val sections = SectionTimes.preset(cn.edu.zhku.schedule.data.CampusPreset.BAIYUN)

    private val timetable = Timetable(
        courses = listOf(
            Course("高等数学II（1）", "席亚讲师", "经管楼209", "经管楼", "5,7-18(周)", (5..18).toList(), listOf(1, 2), "物联网[264-266]班", 1),
            Course("思想道德与法治", "敖叶湘琼讲师", "经管楼207", "经管楼", "5,7-18(周)", (5..18).toList(), listOf(3, 4), "物联网[264-266]班", 1),
            Course("习近平新时代中国特色社会主义思想概论", "蒋海舲", "自动化楼209", "自动化楼", "5,7-18(周)", (5..18).toList(), listOf(8, 9), "物联网[264-266]班", 1),
            Course("大学英语Ⅰ", "段国祥副教授", "教学楼①402", "教学楼①", "7-18(周)", (7..18).toList(), listOf(6, 7), "物联网[264-266]班", 2),
            Course("体育I", "汤淼", "（白云）北田径场", "运动场", "5-18(周)", (5..18).toList(), listOf(3, 4), "物联网264班", 3),
            Course("离散数学", "王若愉", "经管楼209", "经管楼", "7-18(周)", (7..18).toList(), listOf(8, 9), "物联网[264-266]班", 3),
            Course("军事理论", "张正明", "经管楼205", "经管楼", "5,7-18(周)", (5..18).toList(), listOf(8, 9), "物联网[264-266]班", 4),
            Course("实验室安全与防护", "廖玉珍实验师", "教学楼②D1", "教学楼②", "10-12(周)", (10..12).toList(), listOf(10, 11, 12), "物联网[264-266]班", 5)
        ),
        term = "2026-2027-1",
        currentWeek = 13,
        weekCount = 20,
        fetchedAt = System.currentTimeMillis()
    )

    private fun capture(
        name: String,
        dark: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        // 首页有每秒刷新的倒计时，自动推进时钟会永远等不到 idle，这里手动推帧
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ZhkuTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().padding(top = 8.dp)) { content() }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()

        // 直接画 decorView：绕开 Compose 的“等下一帧”逻辑（页面里有每秒刷新的倒计时）
        val view = rule.activity.window.decorView
        if (view.width == 0 || view.height == 0) {
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun homeScreen() {
        capture("home") {
            HomeScreen(
                timetable = timetable,
                sections = sections,
                refreshing = false,
                onRefresh = {},
                onOpenWeek = {}
            )
        }
    }

    @Test
    fun weekScreen() {
        capture("week") {
            WeekScreen(timetable = timetable, sections = sections)
        }
    }

    @Test
    fun homeScreenDark() {
        capture("home_dark", dark = true) {
            HomeScreen(
                timetable = timetable,
                sections = sections,
                refreshing = false,
                onRefresh = {},
                onOpenWeek = {}
            )
        }
    }

    @Test
    fun settingsScreen() {
        val settings = cn.edu.zhku.schedule.data.Settings(
            account = "202620264422",
            hasPassword = true,
            hasCookie = true,
            autoFetch = true,
            fetchMinuteOfDay = 7 * 60 + 30,
            notifyEnabled = true,
            notifyLeadMinutes = 15,
            sections = sections,
            lastFetchAt = System.currentTimeMillis()
        )
        capture("settings") {
            cn.edu.zhku.schedule.ui.SettingsScreen(
                settings = settings,
                currentWeek = 13,
                weekSource = cn.edu.zhku.schedule.data.WeekSource.SERVER,
                refreshing = false,
                onRefresh = {},
                onLogout = {},
                onAutoFetch = {},
                onFetchMinute = {},
                onNotifyEnabled = {},
                onNotifyLead = {},
                onCampus = {},
                onSectionTime = { _, _, _ -> },
                onRelogin = {}
            )
        }
    }

    @Test
    fun settingsLocal() {
        val settings = cn.edu.zhku.schedule.data.Settings(
            mode = cn.edu.zhku.schedule.data.DataMode.LOCAL,
            sourceName = "schedule.json",
            importedAt = System.currentTimeMillis(),
            autoFetch = true,
            notifyEnabled = true,
            notifyLeadMinutes = 15,
            sections = sections
        )
        capture("settings_local") {
            cn.edu.zhku.schedule.ui.SettingsScreen(
                settings = settings,
                currentWeek = 13,
                weekSource = cn.edu.zhku.schedule.data.WeekSource.IMPORT,
                refreshing = false,
                onRefresh = {},
                onLogout = {},
                onAutoFetch = {},
                onFetchMinute = {},
                onNotifyEnabled = {},
                onNotifyLead = {},
                onCampus = {},
                onSectionTime = { _, _, _ -> },
                onRelogin = {}
            )
        }
    }

    @Test
    fun loginScreen() {
        capture("login") {
            cn.edu.zhku.schedule.ui.LoginScreen(
                busy = false,
                onLogin = { _, _ -> },
                onImport = {}
            )
        }
    }

    @Test
    fun homeOffline() {
        capture("home_offline") {
            HomeScreen(
                timetable = timetable,
                sections = sections,
                refreshing = false,
                onRefresh = {},
                onOpenWeek = {},
                banner = "离线模式 · 连不上教务系统，显示本地缓存的课表",
                bannerAction = "重试",
                onBannerAction = {}
            )
        }
    }
}
