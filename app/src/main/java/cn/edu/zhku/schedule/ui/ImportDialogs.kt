package cn.edu.zhku.schedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.zhku.schedule.data.PendingImport

/**
 * 导入确认框：文件解析完先给用户看一眼，并确认它对应第几教学周。
 * 周次是唯一无法从 JSON 里确定的信息（文件只有导出那一刻的周次），所以必须让用户过一眼。
 */
@Composable
fun ImportPreviewDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val maxWeek = maxOf(pending.weekCount, pending.week, 30)
    var week by remember(pending) { mutableIntStateOf(pending.week.coerceIn(1, maxWeek)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入课表") },
        text = {
            Column {
                Text(
                    pending.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${pending.courseCount} 门课程 · ${pending.weekRangeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text("文件对应第几教学周？", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                WeekStepper(value = week, range = 1..maxWeek, onChange = { week = it })
                Spacer(Modifier.height(10.dp))
                Text(
                    "应用会以「导入这一刻 + 该周次」推算每天属于第几周。" +
                        "如果文件不是本周导出的，请把它改成文件实际对应的周次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(week) }) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 手动校正当前教学周（本地模式的课表不会自动更新，周次会随时间跑偏） */
@Composable
fun WeekPickerDialog(
    current: Int,
    weekCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val maxWeek = maxOf(weekCount, current, 30)
    var week by remember { mutableIntStateOf(current.coerceIn(1, maxWeek)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校正当前教学周") },
        text = {
            Column {
                Text("今天（本周一至周日）是第几周？", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                WeekStepper(value = week, range = 1..maxWeek, onChange = { week = it })
                Spacer(Modifier.height(10.dp))
                Text(
                    "保存后以本周为基准，每周一自动 +1，离线也能一直对上；" +
                        "在线模式下一次成功获取课表会重新按教务系统校准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(week) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun WeekStepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { onChange((value - 1).coerceAtLeast(range.first)) },
            enabled = value > range.first,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            "第 $value 周",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceAtMost(range.last)) },
            enabled = value < range.last,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}
