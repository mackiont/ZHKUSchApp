package cn.edu.zhku.schedule.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导入失败时抛这个，message 直接显示给用户 */
class ImportException(message: String) : Exception(message)

/**
 * 本地课表 JSON 的读写。
 *
 * 解析同时兼容两种写法（都来自本项目）：
 *  1. `zhku_schedule.py -o schedule.json` 导出的格式：
 *     `{"weekdays":{...},"courses":[{"name":..,"weeks":"5,7-18(周)","sections":[1,2],"weekday":1}],"params":{"zc":"18"}}`
 *  2. 本应用「导出课表 JSON」生成的同构格式：`weeks` 也可能是整数数组，另带 `weeksText`。
 *
 * 关键点：JSON 里没有学期开学日期，只有导出时教务系统认定的周次（`params.zc`）。
 * 所以导入时用「导入时刻 + 该周次」作为推算基准，之后按自然周往后滚。
 */
object LocalImport {

    const val MAX_BYTES = 4 * 1024 * 1024

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    fun parse(
        raw: String,
        fileName: String = "",
        weekOverride: Int? = null,
        now: Long = System.currentTimeMillis()
    ): Timetable {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw ImportException("文件是空的，没有读到内容")

        val root = runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw ImportException("不是有效的 JSON 文件")
        }
        val obj = root as? JsonObject
            ?: throw ImportException("JSON 顶层应该是一个对象（以 { 开头）")

        val array = obj.array("courses")
            ?: throw ImportException("文件里找不到 courses 课程列表")

        val courses = array.mapNotNull { parseCourse(it) }
        if (courses.isEmpty()) {
            throw ImportException("文件里没有可用的课程（需要 name / sections / weekday 字段）")
        }

        val maxWeek = courses.flatMap { it.weeks }.maxOrNull() ?: 0
        val declaredCount = obj.int("week_count") ?: obj.int("weekCount") ?: 0
        val weekCount = maxOf(20, declaredCount, maxWeek, weekOverride ?: 0)

        val weekInFile = obj.int("currentWeek")
            ?: obj.int("current_week")
            ?: obj.obj("params")?.let { number(it["zc"]) }
            ?: 1

        val term = obj.str("term")
            ?: obj.obj("params")?.str("xnxq01id")
            ?: ""

        return Timetable(
            courses = courses,
            term = term,
            currentWeek = (weekOverride ?: weekInFile).coerceIn(1, weekCount),
            weekCount = weekCount,
            // 以导入时刻为基准：即使文件是几周前导出的，用户也能在确认框里把周次改对
            fetchedAt = now,
            account = "",
            kbjcmsid = "",
            weekSource = WeekSource.IMPORT
        )
    }

    /** 导出为与 Python 版兼容的格式，方便备份、换手机、或再交给 Python 脚本 */
    fun encode(timetable: Timetable): String {
        val body = buildJsonObject {
            put("format", "zhku-schedule")
            put("generated_at", timeLabel(timetable.fetchedAt))
            put("term", timetable.term)
            put("week_count", timetable.weekCount)
            put("currentWeek", timetable.currentWeek)
            put("courses", buildJsonArray {
                timetable.courses.forEach { course ->
                    add(buildJsonObject {
                        put("name", course.name)
                        put("teacher", course.teacher)
                        put("room", course.room)
                        put("building", course.building)
                        put("weeks", course.weeksText.ifBlank { rangeText(course.weeks) })
                        put("sections", buildJsonArray {
                            course.sections.forEach { add(JsonPrimitive(it)) }
                        })
                        put("klass", course.klass)
                        put("weekday", course.weekday)
                    })
                }
            })
            put("params", buildJsonObject { put("zc", timetable.currentWeek.toString()) })
        }
        return pretty.encodeToString(JsonObject.serializer(), body)
    }

    fun suggestedFileName(timetable: Timetable): String =
        if (timetable.currentWeek > 0) "zhku-schedule-第${timetable.currentWeek}周.json"
        else "zhku-schedule.json"

    /** [5,6,7,9] -> "5-7,9(周)" */
    fun rangeText(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.distinct().sorted()
        val parts = ArrayList<String>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = w
            prev = w
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",") + "(周)"
    }

    fun timeLabel(millis: Long): String {
        if (millis <= 0L) return ""
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    // ---------------------------------------------------------------- 内部实现

    private fun parseCourse(element: JsonElement): Course? {
        val obj = element as? JsonObject ?: return null
        val name = obj.str("name") ?: obj.str("courseName") ?: obj.str("course_name") ?: return null
        if (name.isBlank()) return null

        val sections = obj.sectionList()
        if (sections.isEmpty()) return null // 没有节次就无法排进课表

        val weeksText = obj.str("weeksText")
            ?: obj.str("weeks_text")
            ?: obj.str("weeks")
            ?: ""
        val weeks = (obj.array("weeks_list") ?: obj.array("weeks") ?: obj.array("week_list"))
            ?.mapNotNull { number(it) }
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            ?: TimetableParser.parseWeeks(weeksText)

        val weekday = obj.int("weekday") ?: obj.int("day") ?: obj.int("week_day") ?: 1

        return Course(
            name = name.trim(),
            teacher = obj.str("teacher") ?: obj.str("teacherName") ?: "",
            room = obj.str("room") ?: obj.str("place") ?: "",
            building = obj.str("building") ?: "",
            weeksText = weeksText.ifBlank { rangeText(weeks) },
            weeks = weeks,
            sections = sections,
            klass = obj.str("klass") ?: obj.str("class") ?: obj.str("className") ?: "",
            weekday = weekday.coerceIn(1, 7)
        )
    }

    /** 支持 [1,2]、["1","2"]、或 "1,2" 字符串 */
    private fun JsonObject.sectionList(): List<Int> {
        array("sections")?.let { arr ->
            return arr.mapNotNull { number(it) }.filter { it > 0 }.distinct().sorted()
        }
        str("sections")?.let { raw ->
            return Regex("\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }
                .filter { it > 0 }.distinct().sorted().toList()
        }
        return emptyList()
    }

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.content.trim().ifBlank { null }
    }

    private fun JsonObject.int(key: String): Int? = number(this[key])

    private fun number(element: JsonElement?): Int? {
        val value = element as? JsonPrimitive ?: return null
        return value.content.trim().toIntOrNull()
    }
}

/**
 * 导入前的预览数据：文件已经解析完，等用户确认「对应第几周」再落盘。
 */
data class PendingImport(
    val timetable: Timetable,
    val fileName: String
) {
    val courseCount: Int get() = timetable.courses.size
    val week: Int get() = timetable.currentWeek
    val weekCount: Int get() = timetable.weekCount
    val weekRangeLabel: String
        get() {
            val all = timetable.courses.flatMap { it.weeks }
            if (all.isEmpty()) return "未标注周次"
            return "第 ${all.min()}–${all.max()} 周"
        }
}
