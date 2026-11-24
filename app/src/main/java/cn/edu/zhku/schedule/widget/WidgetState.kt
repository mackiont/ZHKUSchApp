package cn.edu.zhku.schedule.widget

import cn.edu.zhku.schedule.data.ClassSlot
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.Timetable
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 小组件当前该显示什么 */
enum class WidgetPhase {
    /** 还没有课表 */
    EMPTY,

    /** 未来三周都没有课 */
    IDLE,

    /** 下一节课还没开始 */
    UPCOMING,

    /** 正在上课 */
    IN_CLASS
}

/**
 * 小组件的展示状态，纯数据、可直接单测。
 *
 * 2×2 只有约 110dp 见方，所以行数被压到最少：状态、课程名、时间+地点、倒计时。
 * 课程名给两行，其余都是一行。
 *
 * 倒计时只显示到**分钟**（「还有 1 小时 20 分」），不做逐秒跳动：
 * 秒级数字既占宽度，又会让小组件看起来一直在变。因此 [nextRefreshAt] 会把
 * 下一个整分也算进刷新时刻里。
 */
data class WidgetState(
    val phase: WidgetPhase,
    val status: String,
    val course: String,
    val meta: String,
    /** 倒计时前缀：「还有」/「还剩」；null 表示不显示倒计时 */
    val countdownPrefix: String? = null,
    val countdownTarget: LocalDateTime? = null,
    /** 状态会变的时刻：上课、下课、跨天 */
    val nextChange: LocalDateTime? = null
) {

    /** 倒计时文案，例如「还有 1 小时 20 分」；null 表示这屏不该有倒计时 */
    fun countdownText(now: LocalDateTime): String? {
        val target = countdownTarget ?: return null
        val prefix = countdownPrefix ?: return null
        return "$prefix ${minutesLabel(now, target)}"
    }

    /**
     * 下一次需要重绘的时刻。
     *
     * 倒计时精确到分钟，所以下一个整分要重绘一次；状态切换（上课 / 下课 / 跨天）
     * 也必须重绘。两者取更早的那个，避免多排一个没必要的闹钟。
     */
    fun nextRefreshAt(now: LocalDateTime): LocalDateTime? {
        val nextMinute = if (countdownTarget == null) {
            null
        } else {
            now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        }
        return listOfNotNull(nextChange, nextMinute).minOrNull()
    }

    companion object {

        fun compute(
            timetable: Timetable?,
            sections: List<SectionTime>,
            now: LocalDateTime
        ): WidgetState {
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay()

            if (timetable == null || timetable.courses.isEmpty()) {
                return WidgetState(
                    phase = WidgetPhase.EMPTY,
                    status = "还没有课表",
                    course = "打开应用登录或导入课表",
                    meta = "",
                    nextChange = midnight
                )
            }

            val current = ScheduleMath.current(timetable, sections, now)
            if (current != null) {
                return WidgetState(
                    phase = WidgetPhase.IN_CLASS,
                    status = "正在上课",
                    course = current.course.name,
                    meta = metaOf("至 ${current.endLabel}", current),
                    countdownPrefix = "还剩",
                    countdownTarget = current.end,
                    nextChange = current.end
                )
            }

            // 21 天内的下一节课；比这更远就当没有课，避免倒计时变成几十小时
            val next = ScheduleMath.upcoming(timetable, sections, now, days = 21)
                .firstOrNull { it.start.isAfter(now) }

            if (next == null) {
                return WidgetState(
                    phase = WidgetPhase.IDLE,
                    status = "近期没有课",
                    course = "好好休息",
                    meta = "",
                    nextChange = midnight
                )
            }

            return WidgetState(
                phase = WidgetPhase.UPCOMING,
                status = "下一节课 · ${ScheduleMath.dateLabel(next.date, now.toLocalDate())}",
                course = next.course.name,
                meta = metaOf(next.startLabel, next),
                countdownPrefix = "还有",
                countdownTarget = next.start,
                nextChange = minOf(next.start, midnight)
            )
        }

        private fun metaOf(timeLabel: String, slot: ClassSlot): String {
            val place = slot.course.place()
            return if (place.isBlank()) timeLabel else "$timeLabel · $place"
        }
    }
}

/**
 * 只到分钟的剩余时间：`1 小时 20 分` / `2 小时` / `45 分`。
 *
 * 向上取整，剩 30 秒时显示「1 分」而不是「0 分」；不足一分钟也按 1 分算。
 */
internal fun minutesLabel(from: LocalDateTime, to: LocalDateTime): String {
    val seconds = Duration.between(from, to).seconds
    val total = ((seconds + 59) / 60).coerceAtLeast(1)
    val hours = total / 60
    val minutes = total % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
        hours > 0 -> "$hours 小时"
        else -> "$minutes 分"
    }
}
