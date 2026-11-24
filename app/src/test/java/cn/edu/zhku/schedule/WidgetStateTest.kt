package cn.edu.zhku.schedule

import cn.edu.zhku.schedule.data.CampusPreset
import cn.edu.zhku.schedule.data.Course
import cn.edu.zhku.schedule.data.SectionTimes
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.widget.WidgetPhase
import cn.edu.zhku.schedule.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 小组件状态机：显示哪节课、倒计时朝哪个时刻、下一次什么时候刷新。
 * 这几条一旦错了，小组件就会停在「下一节课」不动或者倒计时指向错误的时间。
 */
class WidgetStateTest {

    private val sections = SectionTimes.preset(CampusPreset.BAIYUN)

    /** 2026-11-16 是周一，设为第 12 周的基准时刻 */
    private val monday = LocalDateTime.of(2026, 11, 16, 8, 0)

    private fun timetable(weekRange: IntRange = 1..20, week: Int = 12) = Timetable(
        courses = listOf(
            Course("高等数学II（1）", "席亚讲师", "经管楼209", "经管楼", "1-20(周)", weekRange.toList(), listOf(1, 2), "物联网264班", 1),
            Course("大学英语Ⅰ", "段国祥", "教学楼①402", "教学楼①", "1-20(周)", weekRange.toList(), listOf(6, 7), "物联网264班", 1),
            Course("体育I", "汤淼", "北田径场", "运动场", "1-20(周)", weekRange.toList(), listOf(3, 4), "物联网264班", 3)
        ),
        currentWeek = week,
        fetchedAt = monday.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    @Test
    fun upcomingShowsNextClassAndCountsToItsStart() {
        val now = LocalDateTime.of(2026, 11, 16, 7, 0)
        val state = WidgetState.compute(timetable(), sections, now)

        assertEquals(WidgetPhase.UPCOMING, state.phase)
        assertEquals("高等数学II（1）", state.course)
        assertTrue(state.status, state.status.contains("今天"))
        assertEquals("还有", state.countdownPrefix)
        assertEquals("还有 1 小时 40 分", state.countdownText(now))
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 40), state.countdownTarget)
        // 上课那一刻要重绘
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 40), state.nextChange)
        // 时间 + 地点合成一行，2×2 里只放得下这么多
        assertEquals("08:40 · 经管楼209", state.meta)
    }

    @Test
    fun inClassCountsDownToEnd() {
        val now = LocalDateTime.of(2026, 11, 16, 9, 0)
        val state = WidgetState.compute(timetable(), sections, now)

        assertEquals(WidgetPhase.IN_CLASS, state.phase)
        assertEquals("正在上课", state.status)
        assertEquals("还剩", state.countdownPrefix)
        assertEquals("还剩 1 小时 10 分", state.countdownText(now))
        assertEquals("至 10:10 · 经管楼209", state.meta)
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 10), state.countdownTarget)
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 10), state.nextChange)
    }

    @Test
    fun betweenClassesPicksTheLaterOne() {
        val now = LocalDateTime.of(2026, 11, 16, 12, 0)
        val state = WidgetState.compute(timetable(), sections, now)

        assertEquals(WidgetPhase.UPCOMING, state.phase)
        assertEquals("大学英语Ⅰ", state.course)
        assertEquals("13:30 · 教学楼①402", state.meta)
    }

    @Test
    fun classTomorrowAlsoRefreshesAtMidnight() {
        // 周日晚上：明天 08:40 才上课，但「今天/明天」的文案要在零点换掉
        val now = LocalDateTime.of(2026, 11, 22, 21, 0)
        val state = WidgetState.compute(timetable(), sections, now)

        assertEquals(WidgetPhase.UPCOMING, state.phase)
        assertTrue(state.status, state.status.contains("明天"))
        assertEquals(LocalDateTime.of(2026, 11, 23, 0, 0), state.nextChange)
    }

    @Test
    fun noClassInTheNextThreeWeeks() {
        val now = LocalDateTime.of(2026, 11, 16, 9, 0)
        val state = WidgetState.compute(timetable(weekRange = 1..3), sections, now)

        assertEquals(WidgetPhase.IDLE, state.phase)
        assertEquals("近期没有课", state.status)
        assertNull(state.countdownTarget)
        assertEquals(LocalDateTime.of(2026, 11, 17, 0, 0), state.nextChange)
    }

    @Test
    fun emptyTimetableShowsEntryHint() {
        val state = WidgetState.compute(null, sections, LocalDateTime.of(2026, 11, 16, 9, 0))

        assertEquals(WidgetPhase.EMPTY, state.phase)
        assertEquals("还没有课表", state.status)
        assertNull(state.countdownText(LocalDateTime.of(2026, 11, 16, 9, 0)))
    }

    @Test
    fun countdownRoundsUpToWholeMinutes() {
        // 7:20:40 → 距 08:40 还剩 79 分 20 秒，向上取整成 80 分
        val now = LocalDateTime.of(2026, 11, 16, 7, 20, 40)
        val state = WidgetState.compute(timetable(), sections, now)
        assertEquals("还有 1 小时 20 分", state.countdownText(now))

        // 不足一分钟也显示「1 分」，不会出现「0 分」
        val almost = LocalDateTime.of(2026, 11, 16, 8, 39, 30)
        val near = WidgetState.compute(timetable(), sections, almost)
        assertEquals("还有 1 分", near.countdownText(almost))
    }

    @Test
    fun refreshHappensAtNextWholeMinute() {
        // 倒计时只到分钟，所以下一个整分就要重绘一次；比状态切换早，取 7:01
        val now = LocalDateTime.of(2026, 11, 16, 7, 0, 15)
        val state = WidgetState.compute(timetable(), sections, now)
        assertEquals(LocalDateTime.of(2026, 11, 16, 7, 1), state.nextRefreshAt(now))

        // 临近上课时，状态切换（8:40）比下一个整分更早
        val late = LocalDateTime.of(2026, 11, 16, 8, 39, 50)
        val near = WidgetState.compute(timetable(), sections, late)
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 40), near.nextRefreshAt(late))

        // 没有倒计时（没有课表）时只按状态切换刷新
        val empty = WidgetState.compute(null, sections, now)
        assertEquals(LocalDateTime.of(2026, 11, 17, 0, 0), empty.nextRefreshAt(now))
    }
}
