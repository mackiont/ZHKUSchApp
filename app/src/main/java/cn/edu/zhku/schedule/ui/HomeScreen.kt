package cn.edu.zhku.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.zhku.schedule.data.ClassSlot
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.ui.theme.courseColor
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    timetable: Timetable?,
    sections: List<SectionTime>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenWeek: () -> Unit,
    /** 本地模式 / 离线兜底时的顶部提示 */
    banner: String? = null,
    bannerAction: String? = null,
    onBannerAction: (() -> Unit)? = null
) {
    // 每秒刷新“现在”，倒计时才会走
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }

    val today = now.toLocalDate()
    val todaySlots = remember(timetable, sections, today) {
        timetable?.let { ScheduleMath.slotsFor(it, sections, today) }.orEmpty()
    }
    val current = todaySlots.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
    val upcoming = timetable?.let { ScheduleMath.upcoming(it, sections, now, 14) }.orEmpty()
    val nextSlot = current ?: upcoming.firstOrNull()

    val remainingToday = todaySlots.filter { now.isBefore(it.start) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (banner != null) {
                item {
                    OfflineBanner(
                        text = banner,
                        action = bannerAction,
                        onAction = onBannerAction
                    )
                }
            }

            item {
                if (timetable == null) {
                    EmptyTimetableCard(refreshing = refreshing, onRefresh = onRefresh)
                } else {
                    NextClassCard(slot = nextSlot, now = now, inProgress = current != null)
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "今日剩余课程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenWeek) {
                        Text("本周课表")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (remainingToday.isEmpty()) {
                item { NothingLeftCard(hasClassesToday = todaySlots.isNotEmpty()) }
            } else {
                items(remainingToday, key = { it.id }) { slot ->
                    TodayCourseRow(slot = slot, now = now)
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun OfflineBanner(text: String, action: String?, onAction: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.onSecondaryContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
            )
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun NextClassCard(slot: ClassSlot?, now: LocalDateTime, inProgress: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        slot == null -> "近期没有课"
                        inProgress -> "正在上课"
                        else -> "最近一节课"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f)
                )
                if (slot != null) {
                    CountdownPill(
                        text = if (inProgress) ScheduleMath.remainingText(slot, now)
                        else ScheduleMath.countdownText(slot.start, now)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (slot == null) {
                Text(
                    "未来两周没有排课",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "下拉可以重新获取课表",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            } else {
                Text(
                    slot.course.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                InfoLine(
                    icon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) },
                    text = "${dayLabel(slot.date)} · ${slot.rangeLabel} · ${slot.course.sectionsLabel()}",
                    tint = scheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(6.dp))
                InfoLine(
                    icon = { Icon(Icons.Default.Place, null, Modifier.size(18.dp)) },
                    text = slot.course.place().ifBlank { "地点待定" },
                    tint = scheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                if (slot.course.teacher.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        slot.course.teacher,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownPill(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface.copy(alpha = 0.55f),
        contentColor = scheme.onPrimaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InfoLine(
    icon: @Composable () -> Unit,
    text: String,
    tint: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.CenterStart) { icon() }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun TodayCourseRow(slot: ClassSlot, now: LocalDateTime) {
    val scheme = MaterialTheme.colorScheme
    val accent = courseColor(slot.course.name)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        slot.course.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        ScheduleMath.countdownText(slot.start, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(slot.rangeLabel)
                        append(" · ")
                        append(slot.course.sectionsLabel())
                        if (slot.course.place().isNotBlank()) {
                            append(" · ")
                            append(slot.course.place())
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun NothingLeftCard(hasClassesToday: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (hasClassesToday) "今天的课都上完了" else "今天没有课",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasClassesToday) "好好休息，明天见" else "可以看看本周课表安排",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyTimetableCard(refreshing: Boolean, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "还没有课表数据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "连上网络后点一下获取，或在设置里开启每天自动获取",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp), tint = scheme.primary)
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(if (refreshing) "正在获取…" else "立即获取课表")
                }
            }
        }
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> "${ScheduleMath.weekdayLabel(date.dayOfWeek.value)} ${date.monthValue}月${date.dayOfMonth}日"
    }
}
