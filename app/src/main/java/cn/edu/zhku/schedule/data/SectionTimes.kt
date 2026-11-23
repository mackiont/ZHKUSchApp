package cn.edu.zhku.schedule.data

import kotlinx.serialization.Serializable
import java.time.LocalTime

/** 一节课的起止时间，用"当天第几分钟"存储，方便比较与序列化 */
@Serializable
data class SectionTime(val start: Int, val end: Int) {
    val startLabel: String get() = SectionTimes.label(start)
    val endLabel: String get() = SectionTimes.label(end)
    val rangeLabel: String get() = "$startLabel–$endLabel"
}

/**
 * 节次时间表。
 *
 * 时间取自仲恺农业工程学院教务处《课堂教学时间及课间休息时间表》：
 *  - 海珠校区沿用 2019/2024 版（第一节 08:00 起）
 *  - 白云校区 2024 版（第一节 08:40 起，中午 13:30 起）
 *
 * 注意：白云校区时间表里没有"第五节"（中午休息），这里保留一个占位时间，
 * 避免个别课表出现第五节时无法显示。用户可在设置里逐节修改。
 */
object SectionTimes {

    fun label(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun parseLabel(text: String): Int? {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(text.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h > 23 || min > 59) return null
        return h * 60 + min
    }

    fun preset(campus: CampusPreset): List<SectionTime> = when (campus) {
        CampusPreset.BAIYUN -> listOf(
            SectionTime(8 * 60 + 40, 9 * 60 + 20),      // 一
            SectionTime(9 * 60 + 30, 10 * 60 + 10),     // 二
            SectionTime(10 * 60 + 30, 11 * 60 + 10),    // 三
            SectionTime(11 * 60 + 20, 12 * 60 + 0),     // 四
            SectionTime(12 * 60 + 10, 12 * 60 + 50),    // 五（白云无，占位）
            SectionTime(13 * 60 + 30, 14 * 60 + 10),    // 六
            SectionTime(14 * 60 + 20, 15 * 60 + 0),     // 七
            SectionTime(15 * 60 + 20, 16 * 60 + 0),     // 八
            SectionTime(16 * 60 + 10, 16 * 60 + 50),    // 九
            SectionTime(19 * 60 + 0, 19 * 60 + 40),     // 十
            SectionTime(19 * 60 + 50, 20 * 60 + 30),    // 十一
            SectionTime(20 * 60 + 40, 21 * 60 + 20)     // 十二
        )

        CampusPreset.HAIZHU -> listOf(
            SectionTime(8 * 60 + 0, 8 * 60 + 40),
            SectionTime(8 * 60 + 50, 9 * 60 + 30),
            SectionTime(9 * 60 + 50, 10 * 60 + 30),
            SectionTime(10 * 60 + 40, 11 * 60 + 20),
            SectionTime(11 * 60 + 30, 12 * 60 + 10),
            SectionTime(14 * 60 + 30, 15 * 60 + 10),
            SectionTime(15 * 60 + 20, 16 * 60 + 0),
            SectionTime(16 * 60 + 20, 17 * 60 + 0),
            SectionTime(17 * 60 + 10, 17 * 60 + 50),
            SectionTime(19 * 60 + 30, 20 * 60 + 10),
            SectionTime(20 * 60 + 20, 21 * 60 + 0),
            SectionTime(21 * 60 + 10, 21 * 60 + 50)
        )
    }

    fun encode(list: List<SectionTime>): String =
        list.joinToString("|") { "${label(it.start)}-${label(it.end)}" }

    fun decode(text: String): List<SectionTime>? {
        if (text.isBlank()) return null
        val out = ArrayList<SectionTime>()
        for (part in text.split("|")) {
            val bits = part.split("-")
            if (bits.size != 2) continue
            val s = parseLabel(bits[0]) ?: continue
            val e = parseLabel(bits[1]) ?: continue
            out.add(SectionTime(s, e))
        }
        return out.takeIf { it.size >= 6 }
    }

    /** 第 n 节（从 1 开始）的开始时间，越界返回 null */
    fun startOf(list: List<SectionTime>, section: Int): Int? = list.getOrNull(section - 1)?.start
    fun endOf(list: List<SectionTime>, section: Int): Int? = list.getOrNull(section - 1)?.end

    fun nowMinute(now: LocalTime): Int = now.hour * 60 + now.minute
}
