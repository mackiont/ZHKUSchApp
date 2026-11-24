package cn.edu.zhku.schedule.widget

import cn.edu.zhku.schedule.data.ClassSlot
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.Timetable
import java.time.LocalDateTime

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
 * [nextChange] 是「下一次需要重新渲染的时刻」：上课、下课、跨天（今天/明天 会变）。
 * 只在三个时刻各设一个闹钟就够了 —— 倒计时本身由 Chronometer 自己按秒走，不需要每分钟唤醒。
 */
data class WidgetState(
    val phase: WidgetPhase,
    val status: String,
    val course: String,
    val meta: String,
    /** Chronometer 的格式串（含一个 %s），null 表示不显示倒计时 */
    val countdownFormat: String? = null,
    val countdownTarget: LocalDateTime? = null,
    val nextChange: LocalDateTime? = null
) {

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
                    countdownFormat = "还剩 %s",
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
                countdownFormat = "还有 %s",
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
