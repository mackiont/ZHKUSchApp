package cn.edu.zhku.schedule.data

import java.io.Serializable

data class ParsedPage(
    val courses: List<Course> = emptyList(),
    val term: String = "",
    val currentWeek: Int = 1,
    val weekCount: Int = 20,
    val kbjcmsid: String = ""
)

/**
 * 强智教务（仲恺 edu-admin.zhku.edu.cn）课表页解析器。
 *
 * 与 zhku_schedule.py 中的 parse_timetable 逻辑保持一致，要点：
 *  1. 表格 id 是 `timetable`（不是网上常见的 `kbtable`）
 *  2. 单元格里两套 div：`kbcontent1`(简版) / `kbcontent`(全版，含教师)，取全版
 *  3. 字段靠 `<font title='教师'>` 这类语义属性识别，比按行猜位置可靠
 *  4. 一格多门课用一排短横线分隔；`div.item-box` 二维码块要先剔除
 *  5. 节次从 `[01-02节]` 里取，比行首中文"第一二节"准
 */
object TimetableParser {

    private val S = RegexOption.DOT_MATCHES_ALL
    private val I = RegexOption.IGNORE_CASE
    private val FLAGS = setOf(S, I)

    private val ROW_RE = Regex("<tr[^>]*>(.*?)</tr>", FLAGS)
    private val CELL_RE = Regex("<t([dh])([^>]*)>(.*?)</t[dh]>", FLAGS)
    private val SPAN_RE = Regex("(rowspan|colspan)\\s*=\\s*[\"']?(\\d+)", I)
    private val WEEKDAY_RE = Regex("星期([一二三四五六日天])")
    private val ITEM_BOX_RE = Regex("<div[^>]*class=['\"]item-box['\"][^>]*>.*?</div>", FLAGS)
    private val CONTENT_DIV_RE =
        Regex("<div[^>]*class=[\"']\\s*(kbcontent1?)\\s*[\"'][^>]*>(.*?)</div>", FLAGS)
    private val FONT_RE = Regex("<font([^>]*)>(.*?)</font>", FLAGS)
    private val SEP_RE = Regex("-{5,}")
    private val SEC_IN_WEEK_RE = Regex("\\[\\s*([\\d,\\-]+)\\s*节\\s*]")
    private val WEEK_PART_RE = Regex("^(.*?)[（(]\\s*([^)）]*周)\\s*[)）]")
    private val TITLE_RE = Regex("""title=['"]([^'"]*)['"]""", I)
    private val NAME_ATTR_RE = Regex("""name=['"]([^'"]*)['"]""", I)
    private val OPTION_RE = Regex("<option([^>]*)>(.*?)</option>", FLAGS)
    private val SELECT_RE = Regex("<select([^>]*)>(.*?)</select>", FLAGS)
    private val ID_ATTR_RE = Regex("""id\s*=\s*['"]([^'"]*)['"]""", I)
    private val VALUE_ATTR_RE = Regex("""value\s*=\s*['"]([^'"]*)['"]""", I)

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val WEEKDAY_MAP = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )

    private val IGNORED_NAME_ATTRS =
        setOf("wkxx", "jxlmc", "tzdbh", "ktmcstr", "bzstr", "xsks")

    // ---------------------------------------------------------------- 入口

    fun parse(html: String): ParsedPage {
        val grid = parseTable(html, "timetable")
            ?: parseTable(html, "kbtable")
            ?: parseTable(html, "")
            ?: return ParsedPage(term = termOf(html), currentWeek = weekOf(html), weekCount = weekCountOf(html))

        val col2weekday = linkedMapOf<Int, Int>()
        var headerRow = -1
        for (r in 0 until minOf(3, grid.size)) {
            var found = 0
            grid[r].forEachIndexed { c, cell ->
                if (cell == null) return@forEachIndexed
                val m = WEEKDAY_RE.find(cleanText(cell.html))
                val ch = m?.groupValues?.get(1)?.firstOrNull()
                if (ch != null) {
                    WEEKDAY_MAP[ch]?.let {
                        col2weekday[c] = it
                        found++
                    }
                }
            }
            if (found >= 3) {
                headerRow = r
                break
            }
        }

        val courses = ArrayList<Course>()
        val seen = HashSet<String>()
        for (r in (headerRow + 1) until grid.size) {
            val row = grid[r]
            if (row.isEmpty()) continue
            val rowSections = row.firstOrNull()?.let { cnToInts(cleanText(it.html)) } ?: emptyList()
            row.forEachIndexed { c, cell ->
                if (cell == null || c == 0) return@forEachIndexed
                val weekday = col2weekday[c] ?: return@forEachIndexed
                if (cell.row != r) return@forEachIndexed
                if (!cell.html.contains("kbcontent", ignoreCase = true)) return@forEachIndexed
                for (course in parseCell(cell.html, rowSections)) {
                    val key = "${course.name}|${course.weeksText}|${course.room}|$weekday"
                    if (!seen.add(key)) continue
                    courses.add(course.copy(weekday = weekday))
                }
            }
        }

        courses.sortWith(compareBy({ it.weekday }, { it.firstSection }))
        return ParsedPage(
            courses = courses,
            term = termOf(html),
            currentWeek = weekOf(html),
            weekCount = weekCountOf(html),
            kbjcmsid = selectedOption(html, "kbjcmsid") ?: ""
        )
    }

    // ---------------------------------------------------------------- 表格

    data class Cell(
        val row: Int,
        val col: Int,
        val rowspan: Int,
        val colspan: Int,
        val tag: String,
        val html: String
    )

    fun parseTable(html: String, tableId: String = "timetable"): List<List<Cell?>>? {
        val inner = pickTable(html, tableId) ?: return null
        val cells = ArrayList<Cell>()
        var rowIdx = 0
        for (rm in ROW_RE.findAll(inner)) {
            val rowHtml = rm.groupValues[1]
            for (cm in CELL_RE.findAll(rowHtml)) {
                val attrs = cm.groupValues[2]
                val spans = SPAN_RE.findAll(attrs).associate {
                    it.groupValues[1].lowercase() to it.groupValues[2].toInt()
                }
                cells.add(
                    Cell(
                        row = rowIdx,
                        col = 0,
                        rowspan = maxOf(1, spans["rowspan"] ?: 1),
                        colspan = maxOf(1, spans["colspan"] ?: 1),
                        tag = "t" + cm.groupValues[1].lowercase(),
                        html = cm.groupValues[3]
                    )
                )
            }
            rowIdx++
        }
        if (cells.isEmpty()) return null

        val occupied = HashMap<Pair<Int, Int>, Cell>()
        val placed = ArrayList<Cell>(cells.size)
        for (c in cells) {
            var col = 0
            while (occupied.containsKey(c.row to col)) col++
            val fixed = c.copy(col = col)
            for (dr in 0 until fixed.rowspan) {
                for (dc in 0 until fixed.colspan) {
                    occupied[fixed.row + dr to col + dc] = fixed
                }
            }
            placed.add(fixed)
        }

        val nRows = occupied.keys.maxOf { it.first } + 1
        val nCols = occupied.keys.maxOf { it.second } + 1
        return (0 until nRows).map { r -> (0 until nCols).map { c -> occupied[r to c] } }
    }

    private fun pickTable(html: String, tableId: String): String? {
        if (tableId.isNotEmpty()) {
            val re = Regex(
                "<table[^>]*id\\s*=\\s*[\"']${Regex.escape(tableId)}[\"'][^>]*>(.*?)</table>",
                FLAGS
            )
            re.find(html)?.let { return it.groupValues[1] }
        }
        for (m in Regex("<table[^>]*>(.*?)</table>", FLAGS).findAll(html)) {
            if (m.groupValues[1].contains("kbcontent", ignoreCase = true)) return m.groupValues[1]
        }
        return null
    }

    // ---------------------------------------------------------------- 单元格

    fun parseCell(cellHtml: String, rowSections: List<Int>): List<Course> {
        val cleaned = ITEM_BOX_RE.replace(cellHtml, "")

        val all = CONTENT_DIV_RE.findAll(cleaned).map { it.groupValues[1].lowercase() to it.groupValues[2] }.toList()
        val full = all.filter { it.first == "kbcontent" }.map { it.second }
        val segments = full.ifEmpty { all.map { it.second } }.ifEmpty { listOf(cleaned) }

        val out = ArrayList<Course>()
        for (seg in segments) {
            for (part in SEP_RE.split(seg)) {
                val items = fontItems(part)
                if (items.isEmpty()) continue
                var name = ""
                var teacher = ""
                var weeksText = ""
                var room = ""
                var building = ""
                var klass = ""
                var sections = emptyList<Int>()

                for (item in items) {
                    when (item.title) {
                        "教师" -> teacher = item.text
                        "周次(节次)", "周次（节次）" -> {
                            SEC_IN_WEEK_RE.find(item.text)?.let { m ->
                                sections = Regex("\\d+").findAll(m.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
                            }
                            val m2 = WEEK_PART_RE.find(item.text)
                            weeksText = m2?.value ?: item.text
                        }
                        "教室" -> room = item.text
                        "教学楼" -> building = item.text.trim('【', '】')
                        "班级" -> klass = item.text.replace(Regex("^班级\\s*[:：]\\s*"), "")
                        else -> if (item.title.isEmpty() && item.name !in IGNORED_NAME_ATTRS) {
                            if (name.isEmpty()) name = item.text
                        }
                    }
                }
                if (name.isEmpty()) continue
                val secs = (sections.ifEmpty { rowSections }).distinct().sorted()
                out.add(
                    Course(
                        name = name,
                        teacher = teacher,
                        room = room,
                        building = building,
                        weeksText = weeksText,
                        weeks = parseWeeks(weeksText),
                        sections = secs,
                        klass = klass
                    )
                )
            }
        }
        return out
    }

    private data class FontItem(val title: String, val name: String, val text: String)

    private fun fontItems(segment: String): List<FontItem> = FONT_RE.findAll(segment).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val title = TITLE_RE.find(attrs)?.groupValues?.get(1) ?: ""
        val name = NAME_ATTR_RE.find(attrs)?.groupValues?.get(1) ?: ""
        val text = cleanText(m.groupValues[2]).trim()
        if (text.isEmpty()) null else FontItem(title, name, text)
    }.toList()

    // ---------------------------------------------------------------- 文本工具

    fun cleanText(s: String): String {
        var t = Regex("<br\\s*/?>", I).replace(s, "\n")
        t = Regex("<[^>]+>").replace(t, "")
        return t.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
    }

    /** "第一二节" -> [1,2]；"第十十一十二节" -> [10,11,12] */
    fun cnToInts(s: String): List<Int> {
        val t = s.replace("第", "").replace("节", "").replace("大节", "").trim()
        val out = ArrayList<Int>()
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch == '十') {
                val next = t.getOrNull(i + 1)
                if (next != null && CN_DIGITS.containsKey(next)) {
                    out.add(10 + (CN_DIGITS[next] ?: 0))
                    i += 2
                } else {
                    out.add(10)
                    i += 1
                }
                continue
            }
            val digit = CN_DIGITS[ch]
            if (digit != null) {
                if (t.getOrNull(i + 1) == '十') {
                    val base = digit * 10
                    val third = t.getOrNull(i + 2)
                    if (third != null && CN_DIGITS.containsKey(third)) {
                        out.add(base + (CN_DIGITS[third] ?: 0))
                        i += 3
                    } else {
                        out.add(base)
                        i += 2
                    }
                    continue
                }
                out.add(digit)
            }
            i++
        }
        return out
    }

    /**
     * 周次文本 -> 周次列表，支持 "5,7-18(周)"、"5,7-8,10-12,14-18(周)"、
     * "1-16(单周)"、"1-16(双周)" 等写法。解析失败返回空列表（表示每周都上）。
     */
    fun parseWeeks(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        val odd = raw.contains("单")
        val even = raw.contains("双")
        val out = sortedSetOf<Int>()
        for (token in raw.split(',', '，', '、')) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val range = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)").find(t)
            if (range != null) {
                val a = range.groupValues[1].toIntOrNull() ?: continue
                val b = range.groupValues[2].toIntOrNull() ?: continue
                for (w in a..b) {
                    if (odd && w % 2 == 0) continue
                    if (even && w % 2 == 1) continue
                    out.add(w)
                }
            } else {
                Regex("\\d+").find(t)?.value?.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- 页面控件

    fun selectedOption(html: String, selectId: String): String? = selectOptions(html, selectId)
        .firstOrNull { it.second }?.first

    fun termOf(html: String): String =
        selectedOption(html, "xnxq01id")
            ?: selectOptions(html, "xnxq01id").map { it.first }.firstOrNull { it.isNotBlank() }
            ?: ""

    fun weekOf(html: String): Int =
        selectedOption(html, "zc")?.toIntOrNull() ?: 0

    fun weekCountOf(html: String): Int =
        selectOptions(html, "zc").mapNotNull { it.first.toIntOrNull() }.maxOrNull() ?: 20

    fun selectOptions(html: String, selectId: String): List<Pair<String, Boolean>> {
        for (sel in SELECT_RE.findAll(html)) {
            val attrs = sel.groupValues[1]
            val id = ID_ATTR_RE.find(attrs)?.groupValues?.get(1) ?: continue
            if (id != selectId) continue
            return OPTION_RE.findAll(sel.groupValues[2]).map { om ->
                val oAttrs = om.groupValues[1]
                val value = VALUE_ATTR_RE.find(oAttrs)?.groupValues?.get(1) ?: ""
                val selected = oAttrs.contains("selected", ignoreCase = true)
                value to selected
            }.toList()
        }
        return emptyList()
    }

    fun looksLikeLogin(html: String): Boolean =
        html.contains("loginForm") || html.contains("btn-login-new")
}
