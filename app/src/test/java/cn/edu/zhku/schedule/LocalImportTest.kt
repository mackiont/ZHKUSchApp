package cn.edu.zhku.schedule

import cn.edu.zhku.schedule.data.ImportException
import cn.edu.zhku.schedule.data.LocalImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 本地课表 JSON 的解析测试。样本来自 `zhku_schedule.py -o schedule.json` 的真实输出。
 */
class LocalImportTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("sample_schedule.json")!!
            .bufferedReader().readText()

    @Test
    fun parsesPythonExport() {
        val tt = LocalImport.parse(sample(), "schedule.json")

        assertEquals(10, tt.courses.size)
        // params.zc = 18：导出时教务系统认为在第 18 周
        assertEquals(18, tt.currentWeek)

        val math = tt.courses.first()
        assertEquals("高等数学II（1）", math.name)
        assertEquals("经管楼209", math.room)
        assertEquals(1, math.weekday)
        assertEquals(listOf(1, 2), math.sections)
        assertEquals("5,7-18(周)", math.weeksText)
        assertEquals(listOf(5) + (7..18).toList(), math.weeks)

        // 双段周次也要展开正确
        val military = tt.courses.first { it.name == "军事理论" }
        assertEquals(listOf(5, 7, 8, 10, 11, 12, 14, 15, 16, 17, 18), military.weeks)
    }

    @Test
    fun importedWeekBecomesCurrentWeek() {
        val now = System.currentTimeMillis()
        val tt = LocalImport.parse(sample(), "schedule.json", now = now)
        assertEquals(18, tt.weekAt(LocalDate.now()))
        // 一周后自动滚到第 19 周
        assertEquals(19, tt.weekAt(LocalDate.now().plusWeeks(1)))
        assertEquals(now, tt.fetchedAt)
    }

    @Test
    fun weekOverrideWins() {
        val tt = LocalImport.parse(sample(), "old.json", weekOverride = 12)
        assertEquals(12, tt.currentWeek)
        assertEquals(12, tt.weekAt(LocalDate.now()))
    }

    @Test
    fun parsesAppExportWithWeekArrays() {
        val json = """
            {
              "format": "zhku-schedule",
              "currentWeek": 3,
              "week_count": 20,
              "courses": [
                {"name":"线性代数","teacher":"某老师","room":"A101","weeks":[1,2,3,4],
                 "sections":[5,6],"klass":"某班","weekday":4}
              ]
            }
        """.trimIndent()

        val tt = LocalImport.parse(json)
        assertEquals(1, tt.courses.size)
        assertEquals(3, tt.currentWeek)
        assertEquals(listOf(1, 2, 3, 4), tt.courses[0].weeks)
        assertEquals(listOf(5, 6), tt.courses[0].sections)
        assertEquals("1-4(周)", tt.courses[0].weeksText)
    }

    @Test
    fun encodeRoundTrip() {
        val original = LocalImport.parse(sample(), "schedule.json")
        val encoded = LocalImport.encode(original)
        val again = LocalImport.parse(encoded, "export.json")

        assertEquals(original.courses.size, again.courses.size)
        assertEquals(original.currentWeek, again.currentWeek)
        assertEquals(original.courses.map { it.name }, again.courses.map { it.name })
        assertEquals(original.courses.map { it.weeks }, again.courses.map { it.weeks })
        assertEquals(original.courses.map { it.sections }, again.courses.map { it.sections })
        assertEquals(original.courses.map { it.weekday }, again.courses.map { it.weekday })
    }

    @Test
    fun rejectsBrokenFiles() {
        listOf(
            "" to "空文件",
            "not json" to "非 JSON",
            "{}" to "没有 courses",
            """{"courses":[]}""" to "空课程"
        ).forEach { (raw, hint) ->
            val error = runCatching { LocalImport.parse(raw) }.exceptionOrNull()
            assertNotNull("$hint 应当报错", error)
            assertTrue("$hint 应抛出 ImportException，实际 $error", error is ImportException)
        }
    }

    @Test
    fun rangeTextCompacts() {
        assertEquals("1-3,5,7-9(周)", LocalImport.rangeText(listOf(1, 2, 3, 5, 7, 8, 9)))
        assertEquals("4(周)", LocalImport.rangeText(listOf(4)))
        assertEquals("", LocalImport.rangeText(emptyList()))
    }
}
