package cn.edu.zhku.schedule

import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.data.TimetableParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 离线解析测试：用 tests/sample_kb.html 这份真实结构的课表页，
 * 断言与 Python 版 tests/test_parse.py 保持一致。
 */
class TimetableParserTest {

    private val html: String by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("sample_kb.html")!!
        stream.bufferedReader(Charsets.UTF_8).readText()
    }

    @Test
    fun parsesAllCourses() {
        val page = TimetableParser.parse(html)
        assertEquals("应解析出 4 条课程", 4, page.courses.size)
    }

    @Test
    fun parsesBasicFields() {
        val math = TimetableParser.parse(html).courses.first { it.name == "高等数学" }
        assertEquals(1, math.weekday)
        assertEquals(listOf(1, 2), math.sections)
        assertEquals("张三教授", math.teacher)
        assertEquals("经管楼209", math.room)
        assertEquals("1-16(周)", math.weeksText)
        assertEquals("经管楼", math.building)
        assertEquals((1..16).toList(), math.weeks)
    }

    @Test
    fun parsesTwoCoursesInOneCell() {
        val courses = TimetableParser.parse(html).courses
        val english = courses.first { it.name == "大学英语" }
        assertEquals(2, english.weekday)
        assertEquals(listOf(3, 4), english.sections)
        assertEquals("李四副教授", english.teacher)
        assertEquals("教学楼①402", english.room)
        assertEquals("7-8(周)", english.weeksText)

        val discrete = courses.first { it.name == "离散数学" }
        assertEquals("王五讲师", discrete.teacher)
        assertEquals("9,13(单周)", discrete.weeksText)
        assertEquals(listOf(9, 13), discrete.weeks)
    }

    @Test
    fun parsesTripleSection() {
        val lab = TimetableParser.parse(html).courses.first { it.name == "实验室安全与防护" }
        assertEquals(listOf(10, 11, 12), lab.sections)
        assertEquals("廖玉珍实验师", lab.teacher)
    }

    @Test
    fun ignoresQrCodeBlock() {
        for (course in TimetableParser.parse(html).courses) {
            val joined = course.name + course.teacher + course.room
            assertTrue("二维码块内容不应混入字段：$joined", !joined.contains("ewmck"))
            assertTrue("通知单编号不应混入课程名", !course.name.contains("通知单"))
        }
    }

    @Test
    fun convertsChineseSections() {
        assertEquals(listOf(1, 2), TimetableParser.cnToInts("第一二节"))
        assertEquals(listOf(5), TimetableParser.cnToInts("第五节"))
        assertEquals(listOf(10, 11, 12), TimetableParser.cnToInts("第十十一十二节"))
    }

    @Test
    fun parsesWeekRanges() {
        assertEquals(listOf(5) + (7..18).toList(), TimetableParser.parseWeeks("5,7-18(周)"))
        assertEquals(listOf(5, 7, 8, 10, 11, 12, 14, 15, 16, 17, 18), TimetableParser.parseWeeks("5,7-8,10-12,14-18(周)"))
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), TimetableParser.parseWeeks("1-16(单周)"))
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), TimetableParser.parseWeeks("1-16(双周)"))
        assertTrue(TimetableParser.parseWeeks("").isEmpty())
    }

    @Test
    fun detectsLoginPage() {
        assertTrue(TimetableParser.looksLikeLogin("<form id=\"loginForm\"></form>"))
        assertTrue(!TimetableParser.looksLikeLogin("<table id=\"timetable\"></table>"))
    }
}

class TimetableWeekTest {

    private fun timetable(fetched: LocalDate, week: Int): Timetable {
        val millis = fetched.atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Timetable(currentWeek = week, fetchedAt = millis, weekCount = 20)
    }

    @Test
    fun weekAdvancesWithCalendarWeeks() {
        val monday = LocalDate.of(2026, 9, 7)
        val tt = timetable(monday.plusDays(2), 3) // 周三抓的，第 3 周
        assertEquals(3, tt.weekAt(monday))
        assertEquals(3, tt.weekAt(monday.plusDays(6)))
        assertEquals(4, tt.weekAt(monday.plusDays(7)))
        assertEquals(2, tt.weekAt(monday.minusDays(7)))
    }

    @Test
    fun weekIsClamped() {
        val tt = timetable(LocalDate.of(2026, 9, 7), 1)
        assertEquals(1, tt.weekAt(LocalDate.of(2026, 1, 1)))
    }
}

class ScheduleMathTest {

    private val sections = listOf(
        SectionTime(8 * 60 + 40, 9 * 60 + 20),
        SectionTime(9 * 60 + 30, 10 * 60 + 10),
        SectionTime(10 * 60 + 30, 11 * 60 + 10)
    )

    @Test
    fun countdownTextFormats() {
        val now = LocalDateTime.of(2026, 11, 23, 7, 0)
        assertEquals("还有 1 小时 40 分", ScheduleMath.countdownText(LocalDateTime.of(2026, 11, 23, 8, 40), now))
        assertEquals("还有 5 分 30 秒", ScheduleMath.countdownText(LocalDateTime.of(2026, 11, 23, 7, 5, 30), now))
    }

    @Test
    fun slotsUseSectionTimes() {
        val monday = LocalDate.of(2026, 11, 23)
        val millis = monday.atTime(6, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val course = cn.edu.zhku.schedule.data.Course(
            name = "高等数学",
            weekday = 1,
            sections = listOf(1, 2),
            weeks = (1..16).toList(),
            weeksText = "1-16(周)"
        )
        val tt = Timetable(courses = listOf(course), currentWeek = 1, fetchedAt = millis, weekCount = 20)
        val slots = ScheduleMath.slotsFor(tt, sections, monday)
        assertEquals(1, slots.size)
        assertEquals("08:40–10:10", slots[0].rangeLabel)
        assertTrue(ScheduleMath.slotsFor(tt, sections, monday.plusDays(1)).isEmpty())
    }
}
