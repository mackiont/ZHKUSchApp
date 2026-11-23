package cn.edu.zhku.schedule.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 某个具体日期的某一次课（已换算成真实起止时间） */
data class ClassSlot(
    val course: Course,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val week: Int
) {
    val startLabel: String get() = SectionTimes.label(startMinute)
    val endLabel: String get() = SectionTimes.label(endMinute)
    val rangeLabel: String get() = "$startLabel–$endLabel"

    val start: LocalDateTime get() = date.atStartOfDay().plusMinutes(startMinute.toLong())
    val end: LocalDateTime get() = date.atStartOfDay().plusMinutes(endMinute.toLong())
    val id: String get() = "${course.id}@$date"
}

object ScheduleMath {

    private val WEEKDAY_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日")

    fun weekdayLabel(weekday: Int): String = WEEKDAY_CN[(weekday - 1).coerceIn(0, 6)]

    fun dateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> weekdayLabel(date.dayOfWeek.value)
    }

    fun longDateLabel(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date.dayOfWeek.value)}"

    /** 某一天要上的课，按开始时间排序 */
    fun slotsFor(timetable: Timetable, sections: List<SectionTime>, date: LocalDate): List<ClassSlot> {
        val week = timetable.weekAt(date)
        return timetable.courses
            .filter { it.weekday == date.dayOfWeek.value && it.activeIn(week) && it.sections.isNotEmpty() }
            .mapNotNull { course ->
                val start = SectionTimes.startOf(sections, course.firstSection) ?: return@mapNotNull null
                val end = SectionTimes.endOf(sections, course.lastSection) ?: (start + 45)
                ClassSlot(course, date, start, end, week)
            }
            .sortedBy { it.startMinute }
    }

    /** 从现在起未来 days 天内的课（不含已结束的） */
    fun upcoming(
        timetable: Timetable,
        sections: List<SectionTime>,
        now: LocalDateTime,
        days: Int = 14
    ): List<ClassSlot> {
        val out = ArrayList<ClassSlot>()
        for (offset in 0..days) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            for (slot in slotsFor(timetable, sections, date)) {
                if (slot.end.isAfter(now)) out.add(slot)
            }
            if (out.size >= 40) break
        }
        return out
    }

    /** 正在上的课 */
    fun current(timetable: Timetable, sections: List<SectionTime>, now: LocalDateTime): ClassSlot? =
        slotsFor(timetable, sections, now.toLocalDate())
            .firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }

    /** 倒计时文案：>1 小时给出时分，<1 小时给出分秒 */
    fun countdownText(target: LocalDateTime, now: LocalDateTime): String {
        val d = Duration.between(now, target)
        val totalSeconds = d.seconds.coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            totalSeconds <= 0 -> "即将开始"
            hours > 0 -> "还有 ${hours} 小时 ${minutes} 分"
            minutes > 0 -> "还有 ${minutes} 分 ${seconds} 秒"
            else -> "还有 ${seconds} 秒"
        }
    }

    fun remainingText(slot: ClassSlot, now: LocalDateTime): String {
        val totalMinutes = Duration.between(now, slot.end).toMinutes().coerceAtLeast(0)
        return "还剩 ${totalMinutes} 分钟"
    }

    fun minuteOfDay(now: LocalDateTime): Int = now.hour * 60 + now.minute
}
