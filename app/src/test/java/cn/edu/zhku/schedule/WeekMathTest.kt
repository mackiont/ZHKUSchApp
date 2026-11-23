package cn.edu.zhku.schedule

import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.data.WeekSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周次推算：这个应用不维护「开学日期」，也不跑每周一次的定时任务，
 * 全靠「基准周次 + 基准时刻」按自然周算差值。
 * 所以这里把跨周、跨年、离线放很久、手动改到超出总周数都钉住。
 */
class WeekMathTest {

    /** 2026-03-02 是周一，用一个干净的自然周做基准 */
    private val monday: LocalDate = LocalDate.of(2026, 3, 2)

    private fun millis(date: LocalDate, hour: Int = 10): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun timetable(week: Int = 5, weekCount: Int = 20, fetched: LocalDate = monday) =
        Timetable(currentWeek = week, weekCount = weekCount, fetchedAt = millis(fetched))

    @Test
    fun `同一周的任何一天都是同一周`() {
        val t = timetable(fetched = monday.plusDays(2)) // 周三抓的
        for (offset in 0..6L) {
            assertEquals("偏移 $offset 天", 5, t.weekAt(monday.plusDays(offset)))
        }
    }

    @Test
    fun `到下一个周一自动加一`() {
        val t = timetable()
        assertEquals(6, t.weekAt(monday.plusDays(7)))   // 下周一
        assertEquals(7, t.weekAt(monday.plusWeeks(2)))
        assertEquals(4, t.weekAt(monday.minusWeeks(1))) // 上周
    }

    @Test
    fun `离线放四周也能自己涨`() {
        val t = timetable() // 基准第 5 周
        assertEquals(9, t.weekAt(monday.plusWeeks(4)))
        assertEquals(12, t.weekAt(monday.plusWeeks(7)))
    }

    @Test
    fun `跨年那一周不会算错`() {
        // 2026-12-28 周一，2027-01-04 是下一周的周一
        val t = timetable(week = 18, fetched = LocalDate.of(2026, 12, 30))
        assertEquals(18, t.weekAt(LocalDate.of(2026, 12, 31)))
        assertEquals(18, t.weekAt(LocalDate.of(2027, 1, 3)))  // 周日仍属同一教学周
        assertEquals(19, t.weekAt(LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `手动改成超出总周数不会被夹回去`() {
        val t = timetable(week = 1, weekCount = 20).withWeek(25, WeekSource.MANUAL, millis(monday))
        assertEquals(25, t.currentWeek)
        assertEquals(25, t.weekCount)
        assertEquals(WeekSource.MANUAL, t.weekSource)
        assertEquals(25, t.weekAt(monday))
        assertEquals(26, t.weekAt(monday.plusWeeks(1)))
    }

    @Test
    fun `手动校正后从本周重新起算`() {
        // 三周前抓的第 3 周，今天实际是第 6 周
        val stale = timetable(week = 3)
        assertEquals(6, stale.weekAt(monday.plusWeeks(3)))

        // 把今天校正成第 6 周，基准时刻换成今天
        val fixed = stale.withWeek(6, WeekSource.MANUAL, millis(monday.plusWeeks(3)))
        assertEquals(6, fixed.weekAt(monday.plusWeeks(3)))
        assertEquals(7, fixed.weekAt(monday.plusWeeks(4)))
        assertEquals(5, fixed.weekAt(monday.plusWeeks(2)))
    }

    @Test
    fun `没有基准时刻就按记录的周次显示`() {
        val t = Timetable(currentWeek = 7, weekCount = 20, fetchedAt = 0L)
        assertEquals(7, t.weekAt(monday))
    }

    @Test
    fun `不会退到第一周之前`() {
        val t = timetable(week = 2)
        assertEquals(1, t.weekAt(monday.minusWeeks(5)))
    }
}
