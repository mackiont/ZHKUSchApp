package cn.edu.zhku.schedule.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.zhku.schedule.data.Course
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.SectionTimes
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.ui.theme.courseColor
import cn.edu.zhku.schedule.ui.theme.courseContainerColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private val ROW_HEIGHT = 64.dp
private val TIME_COL = 38.dp
private val MIN_DAY_WIDTH = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    timetable: Timetable?,
    sections: List<SectionTime>,
    /** 由上层带节拍传入，跨到周一时会自动 +1 */
    currentWeek: Int = timetable?.weekAt(LocalDate.now()) ?: 1,
    onAdjustWeek: (Int) -> Unit = {}
) {
    val today = LocalDate.now()
    val weekCount = timetable?.weekCount ?: 20

    var week by rememberSaveable(timetable?.fetchedAt, currentWeek) { mutableIntStateOf(currentWeek) }
    var showWeekDialog by remember { mutableStateOf(false) }
    val safeWeek = week.coerceIn(1, maxOf(weekCount, currentWeek, 1))
    var menuOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Course?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .plusWeeks((safeWeek - currentWeek).toLong())
    val dates = (0..6).map { monday.plusDays(it.toLong()) }

    val weekCourses = remember(timetable, safeWeek) {
        timetable?.courses?.filter { it.activeIn(safeWeek) }.orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        WeekSelector(
            week = safeWeek,
            weekCount = maxOf(weekCount, safeWeek),
            currentWeek = currentWeek,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onSelect = { week = it },
            onBackToCurrent = { week = currentWeek },
            dates = dates,
            onAdjustWeek = { showWeekDialog = true }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        if (timetable == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text("还没有课表数据，先到首页获取", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val rows = maxOf(12, sections.size)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val dayWidth = maxOf((maxWidth - TIME_COL) / 7f, MIN_DAY_WIDTH)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column {
                        DayHeaderRow(dates, today, dayWidth)
                        GridBody(
                            courses = weekCourses,
                            sections = sections,
                            rows = rows,
                            dayWidth = dayWidth,
                            onSelect = { detail = it }
                        )
                    }
                }
            }
            if (weekCourses.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "第 $safeWeek 周没有课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showWeekDialog) {
        WeekPickerDialog(
            current = currentWeek,
            weekCount = maxOf(30, currentWeek, weekCount),
            onDismiss = { showWeekDialog = false },
            onConfirm = { picked ->
                showWeekDialog = false
                week = picked
                onAdjustWeek(picked)
            }
        )
    }

    detail?.let { course ->
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = sheetState
        ) {
            CourseDetail(course, sections)
        }
    }
}

@Composable
private fun WeekSelector(
    week: Int,
    weekCount: Int,
    currentWeek: Int,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    onBackToCurrent: () -> Unit,
    dates: List<LocalDate>,
    onAdjustWeek: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { if (week > 1) onSelect(week - 1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onMenuOpenChange(true) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第 $week 周",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                for (w in 1..weekCount) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (w == currentWeek) "第 $w 周（本周）" else "第 $w 周"
                            )
                        },
                        onClick = {
                            onSelect(w)
                            onMenuOpenChange(false)
                        }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                DropdownMenuItem(
                    text = { Text("本周其实是第几周？校正") },
                    onClick = {
                        onMenuOpenChange(false)
                        onAdjustWeek()
                    }
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (week != currentWeek) {
            TextButton(onClick = onBackToCurrent) { Text("回到本周") }
        }
        Text(
            "${dates.first().monthValue}/${dates.first().dayOfMonth}–${dates.last().monthValue}/${dates.last().dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { if (week < weekCount) onSelect(week + 1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
        }
    }
}

@Composable
private fun DayHeaderRow(dates: List<LocalDate>, today: LocalDate, dayWidth: Dp) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(TIME_COL))
        dates.forEach { date ->
            val isToday = date == today
            Box(
                modifier = Modifier
                    .width(dayWidth)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = if (isToday) scheme.primary else Color.Transparent,
                    contentColor = if (isToday) scheme.onPrimary else scheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            ScheduleMath.weekdayLabel(date.dayOfWeek.value).removePrefix("周"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridBody(
    courses: List<Course>,
    sections: List<SectionTime>,
    rows: Int,
    dayWidth: Dp,
    onSelect: (Course) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val gridColor = scheme.outlineVariant.copy(alpha = 0.45f)
    val totalWidth = TIME_COL + dayWidth * 7f
    val totalHeight = ROW_HEIGHT * rows.toFloat()

    Box(Modifier.width(totalWidth).height(totalHeight)) {
        // 网格线 + 节次列
        Canvas(Modifier.fillMaxSize()) {
            val rowPx = ROW_HEIGHT.toPx()
            val colPx = dayWidth.toPx()
            val timePx = TIME_COL.toPx()
            for (r in 0 until rows) {
                val y = rowPx * r
                drawLine(
                    color = gridColor,
                    start = Offset(timePx, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }
            for (c in 0..7) {
                val x = timePx + colPx * c
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
            }
        }

        // 节次编号与开始时间
        for (r in 0 until rows) {
            Column(
                modifier = Modifier
                    .offset(y = ROW_HEIGHT * r.toFloat())
                    .width(TIME_COL)
                    .height(ROW_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "${r + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant
                )
                val start = sections.getOrNull(r)?.startLabel
                if (start != null) {
                    Text(
                        start,
                        fontSize = 8.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 课程块
        courses.forEach { course ->
            val first = course.firstSection
            val last = course.lastSection
            if (first <= 0 || last < first) return@forEach
            val top = ROW_HEIGHT * (first - 1).toFloat()
            val height = ROW_HEIGHT * (last - first + 1).toFloat()
            val left = TIME_COL + dayWidth * (course.weekday - 1).toFloat()
            CourseBlock(
                course = course,
                modifier = Modifier
                    .offset(x = left, y = top)
                    .width(dayWidth)
                    .height(height)
                    .padding(2.dp),
                onClick = { onSelect(course) }
            )
        }
    }
}

@Composable
private fun CourseBlock(course: Course, modifier: Modifier, onClick: () -> Unit) {
    val accent = courseColor(course.name)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(courseContainerColor(course.name))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(Modifier.padding(start = 6.dp, end = 3.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                course.name,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (course.room.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    course.room,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CourseDetail(course: Course, sections: List<SectionTime>) {
    val scheme = MaterialTheme.colorScheme
    val start = SectionTimes.startOf(sections, course.firstSection)
    val end = SectionTimes.endOf(sections, course.lastSection)
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(courseColor(course.name))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                course.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(16.dp))
        DetailRow("时间", when {
            start != null && end != null ->
                "${ScheduleTimes.fmt(start)}–${ScheduleTimes.fmt(end)} · ${course.sectionsLabel()}"
            else -> course.sectionsLabel()
        })
        DetailRow("地点", course.place().ifBlank { "待定" })
        if (course.teacher.isNotBlank()) DetailRow("教师", course.teacher)
        DetailRow("周次", course.weeksText.ifBlank { "每周" })
        if (course.building.isNotBlank() && course.building != course.room) {
            DetailRow("教学楼", course.building)
        }
        if (course.klass.isNotBlank()) DetailRow("班级", course.klass)
        Spacer(Modifier.height(8.dp))
        Text(
            ScheduleMath.weekdayLabel(course.weekday),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant
        )
    }
}

private object ScheduleTimes {
    fun fmt(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
    }
}
