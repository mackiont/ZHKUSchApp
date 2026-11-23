package cn.edu.zhku.schedule.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 一门课（一个上课时间块）。字段与教务系统课表单元格一一对应。
 *
 * weeksText 保留服务端原始写法（如 "5,7-18(周)"），weeks 是展开后的周次列表，
 * 用来快速判断某周是否上这门课。
 */
@Serializable
data class Course(
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val building: String = "",
    val weeksText: String = "",
    val weeks: List<Int> = emptyList(),
    val sections: List<Int> = emptyList(),
    val klass: String = "",
    val weekday: Int = 1
) {
    val firstSection: Int get() = sections.minOrNull() ?: 0
    val lastSection: Int get() = sections.maxOrNull() ?: 0

    val id: String
        get() = "$name|$weekday|$firstSection|$room|$weeksText"

    fun activeIn(week: Int): Boolean = weeks.isEmpty() || week in weeks

    fun sectionsLabel(): String = when {
        sections.isEmpty() -> "节次未知"
        sections.size == 1 -> "第 ${sections.first()} 节"
        else -> "第 ${sections.first()}-${sections.last()} 节"
    }

    /** 地点：优先教室，退化为教学楼 */
    fun place(): String = room.ifBlank { building }
}

/**
 * 一次抓取得到的完整课表快照。
 *
 * currentWeek 是「基准时刻」（fetchedAt / 校正时刻）所在的教学周，
 * 配合 fetchedAt 就能推算任意一天属于第几周 —— 不需要知道学期开学日期，
 * 也不需要在每周一跑定时任务：跨周靠日期差自然加一，离线放几周也照样准。
 */
@Serializable
data class Timetable(
    val courses: List<Course> = emptyList(),
    val term: String = "",
    val currentWeek: Int = 1,
    val weekCount: Int = 20,
    val fetchedAt: Long = 0L,
    val account: String = "",
    val kbjcmsid: String = "",
    /** 这个周次是从哪来的：教务系统 / 用户手动校正 / 导入文件 */
    val weekSource: WeekSource = WeekSource.SERVER
) {
    /**
     * 推算某一天属于第几教学周。
     * 过了周日进入周一就自动 +1，往回推同理；只保证不小于第 1 周，
     * 不按总周数截断 —— 手动把本周设成第 25 周，下周就该是第 26 周。
     */
    fun weekAt(date: LocalDate): Int {
        if (fetchedAt <= 0L) return currentWeek
        val base = Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val diff = weeksBetween(base, date)
        return (currentWeek + diff).coerceAtLeast(1)
    }

    fun coursesOn(date: LocalDate): List<Course> =
        courses.filter { it.weekday == date.dayOfWeek.value && it.activeIn(weekAt(date)) }

    /** 把「今天」重新定为第 [week] 周：基准时刻换成现在，之后照常自然递增 */
    fun withWeek(week: Int, source: WeekSource, now: Long = System.currentTimeMillis()): Timetable {
        val target = week.coerceAtLeast(1)
        return copy(
            currentWeek = target,
            weekCount = maxOf(weekCount, target),
            fetchedAt = now,
            weekSource = source
        )
    }

    companion object {
        fun weeksBetween(a: LocalDate, b: LocalDate): Int {
            val ma = a.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val mb = b.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return ChronoUnit.WEEKS.between(ma, mb).toInt()
        }
    }
}

/** 周次的来源，用来在设置页解释「这个第几周是谁说了算」 */
@Serializable
enum class WeekSource(val label: String, val detail: String) {
    SERVER("教务系统", "每次在线获取都会按教务系统返回的当前周校准"),
    ESTIMATED("按上次推算", "教务系统这次没标出当前周，用上次的周次按自然周推算"),
    MANUAL("手动校正", "你指定的周次，之后每周一自动 +1"),
    IMPORT("导入文件", "按导入时确认的周次推算，之后每周一自动 +1")
}


/** 用户设置（只读快照，写入走 Prefs） */
data class Settings(
    val account: String = "",
    val hasPassword: Boolean = false,
    val hasCookie: Boolean = false,
    val autoFetch: Boolean = true,
    /** 每天自动获取的时刻，以“当天第几分钟”表示，07:30 -> 450 */
    val fetchMinuteOfDay: Int = 7 * 60 + 30,
    val notifyEnabled: Boolean = true,
    /** 提前多少分钟提醒 */
    val notifyLeadMinutes: Int = 15,
    val campus: CampusPreset = CampusPreset.BAIYUN,
    val sections: List<SectionTime> = SectionTimes.preset(CampusPreset.BAIYUN),
    val lastFetchAt: Long = 0L,
    val lastError: String = "",
    /** 当前数据来源：在线（教务系统）或本地导入的 JSON */
    val mode: DataMode = DataMode.ONLINE,
    /** 本地模式导入的文件名，仅用于展示 */
    val sourceName: String = "",
    /** 本地课表的导入时间 */
    val importedAt: Long = 0L
) {
    val loggedIn: Boolean get() = account.isNotBlank() && (hasPassword || hasCookie)
    val fetchTimeLabel: String get() = SectionTimes.label(fetchMinuteOfDay)
    val isLocal: Boolean get() = mode == DataMode.LOCAL
}

/**
 * 数据来源模式。
 *
 * - [ONLINE]：登录教务系统抓取，可自动更新；抓取失败时用本地缓存兜底。
 * - [LOCAL]：用户自行导入 JSON 课表，完全不联网，自动更新等在线功能不可用。
 */
enum class DataMode(val label: String) {
    ONLINE("在线模式"),
    LOCAL("本地模式")
}

enum class CampusPreset(val label: String) {
    BAIYUN("白云校区"),
    HAIZHU("海珠校区")
}
