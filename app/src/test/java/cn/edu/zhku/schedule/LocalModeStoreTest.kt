package cn.edu.zhku.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.zhku.schedule.data.Course
import cn.edu.zhku.schedule.data.DataMode
import cn.edu.zhku.schedule.data.Prefs
import cn.edu.zhku.schedule.data.ScheduleRepository
import cn.edu.zhku.schedule.data.Timetable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 本地模式的落盘逻辑：导入后模式切换、周次校正、退出清理。
 * 用 Robolectric 跑真实的 DataStore，避免只测到内存里的假实现。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalModeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(context)
    private val repo = ScheduleRepository(context, prefs)

    private val timetable = Timetable(
        courses = listOf(
            Course(
                name = "离散数学",
                teacher = "王若愉",
                room = "经管楼209",
                building = "经管楼",
                weeksText = "7-18(周)",
                weeks = (7..18).toList(),
                sections = listOf(8, 9),
                klass = "物联网[264-266]班",
                weekday = 3
            )
        ),
        currentWeek = 18,
        weekCount = 20
    )

    @Test
    fun defaultsToOnlineMode() = runBlocking {
        // Robolectric 同一个测试类的 DataStore 文件会复用，先清一次保证从默认值开始
        prefs.logout()
        val settings = prefs.settings()
        assertEquals(DataMode.ONLINE, settings.mode)
        assertTrue(!settings.isLocal)
        assertEquals("", settings.sourceName)
        assertEquals(0L, settings.importedAt)
        assertNull(prefs.loadTimetable())
    }

    @Test
    fun commitLocalSwitchesModeAndKeepsWeek() = runBlocking {
        val pending = repo.parseLocal(
            """{"courses":[{"name":"离散数学","weeks":"7-18(周)","sections":[8,9],"weekday":3}],
                "params":{"zc":"18"}}""",
            "schedule.json"
        )
        assertNotNull(pending)
        assertEquals(18, pending.week)

        val saved = repo.commitLocal(pending, week = 18)
        val settings = prefs.settings()

        assertEquals(DataMode.LOCAL, settings.mode)
        assertTrue(settings.isLocal)
        assertEquals("schedule.json", settings.sourceName)
        assertTrue(settings.importedAt > 0)
        assertEquals(18, saved.currentWeek)
        // 导入当天就算第 18 周，之后按自然周滚动
        assertEquals(18, saved.weekAt(LocalDate.now()))
        assertEquals(1, prefs.loadTimetable()!!.courses.size)
    }

    @Test
    fun rebaseWeekMovesBaseline() = runBlocking {
        repo.commitLocal(
            repo.parseLocal(
                """{"courses":[{"name":"离散数学","weeks":"7-18(周)","sections":[8,9],"weekday":3}]}""",
                "schedule.json"
            ),
            week = 1
        )
        assertEquals(1, prefs.loadTimetable()!!.weekAt(LocalDate.now()))

        val rebased = repo.rebaseWeek(9)
        assertNotNull(rebased)
        assertEquals(9, rebased!!.currentWeek)
        assertEquals(9, rebased.weekAt(LocalDate.now()))
        // 课程本身不变
        assertEquals(1, rebased.courses.size)

        // 校正到超过原周数也要生效（不能又被夹回 weekCount）
        val beyond = repo.rebaseWeek(22)
        assertEquals(22, beyond!!.weekAt(LocalDate.now()))
    }

    @Test
    fun switchingToOnlineKeepsCacheForFallback() = runBlocking {
        repo.commitLocal(
            repo.parseLocal(
                """{"courses":[{"name":"离散数学","weeks":"7-18(周)","sections":[8,9],"weekday":3}]}""",
                "schedule.json"
            ),
            week = 18
        )
        repo.switchToOnline()

        assertEquals(DataMode.ONLINE, prefs.settings().mode)
        // 本地课表还在，可作为离线兜底
        assertNotNull(prefs.loadTimetable())
    }

    @Test
    fun logoutClearsLocalDataAndMode() = runBlocking {
        repo.commitLocal(
            repo.parseLocal(
                """{"courses":[{"name":"离散数学","weeks":"7-18(周)","sections":[8,9],"weekday":3}]}""",
                "schedule.json"
            ),
            week = 18
        )
        prefs.logout()

        val settings = prefs.settings()
        assertEquals(DataMode.ONLINE, settings.mode)
        assertEquals("", settings.sourceName)
        assertEquals(0L, settings.importedAt)
        assertNull(prefs.loadTimetable())
    }
}
