package cn.edu.zhku.schedule.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.edu.zhku.schedule.BuildConfig
import cn.edu.zhku.schedule.data.CampusPreset
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.SectionTimes
import cn.edu.zhku.schedule.data.Settings
import cn.edu.zhku.schedule.data.WeekSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    currentWeek: Int,
    weekSource: WeekSource = WeekSource.SERVER,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onAutoFetch: (Boolean) -> Unit,
    onFetchMinute: (Int) -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onNotifyLead: (Int) -> Unit,
    onCampus: (CampusPreset) -> Unit,
    onSectionTime: (Int, Int, Int) -> Unit,
    onRelogin: () -> Unit,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {},
    onSwitchToOnline: () -> Unit = {},
    onAdjustWeek: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var permissionTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionTick++ }

    val notificationsAllowed = remember(permissionTick) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val exactAlarmAllowed = remember(permissionTick) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionTick++ }
    val exactAlarmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionTick++ }

    var showFetchTime by remember { mutableStateOf(false) }
    var editingSection by remember { mutableStateOf<Int?>(null) }
    var showWeekDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------- 数据来源
        item {
            if (settings.isLocal) {
                SettingsCard("数据来源") {
                    SettingRow(
                        title = "本地模式",
                        subtitle = settings.sourceName.ifBlank { "已导入的课表文件" }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingRow(
                        title = "导入时间",
                        subtitle = if (settings.importedAt > 0) formatTime(settings.importedAt) else "未知"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(onClick = onImport) { Text("重新导入") }
                        OutlinedButton(onClick = onSwitchToOnline) { Text("切换到在线模式") }
                    }
                    Row(Modifier.padding(start = 8.dp)) {
                        TextButton(onClick = onExport) { Text("导出课表 JSON") }
                        TextButton(onClick = onLogout) { Text("清除本地课表") }
                    }
                    Hint("本地模式不访问网络：自动获取与在线更新已停用，上课提醒仍可正常使用。")
                }
            } else {
                SettingsCard("账号") {
                    SettingRow(
                        title = "学号",
                        subtitle = settings.account.ifBlank { "未登录" },
                        trailing = {
                            TextButton(onClick = onRelogin) { Text("重新登录") }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingRow(
                        title = "上次获取",
                        subtitle = if (settings.lastFetchAt > 0) formatTime(settings.lastFetchAt) else "还没有获取过"
                    )
                    if (settings.lastError.isNotBlank()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                settings.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(onClick = onRefresh, enabled = !refreshing) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (refreshing) "正在获取…" else "立即获取课表")
                        }
                        OutlinedButton(onClick = onLogout) { Text("退出登录") }
                    }
                    Row(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        TextButton(onClick = onImport) { Text("导入课表 JSON") }
                        TextButton(onClick = onExport) { Text("导出课表 JSON") }
                    }
                }
            }
        }

        // ---------------- 教学周
        item {
            SettingsCard("教学周") {
                SettingRow(
                    title = "本周是第 $currentWeek 周",
                    subtitle = weekSource.detail,
                    onClick = { showWeekDialog = true },
                    trailing = {
                        Text(
                            "校正",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                Hint(
                    "周次按自然周自动递增：周日过完进入周一就自动 +1，不用联网、也不用每周手动改。" +
                        "和教务系统对不上时，点上面那一行改成正确的周数。"
                )
            }
        }

        // ---------------- 自动获取
        item {
            SettingsCard("自动获取") {
                if (settings.isLocal) {
                    SwitchRow(
                        title = "每天定时获取本周课表",
                        subtitle = "本地模式下不可用（应用不联网）",
                        checked = false,
                        enabled = false,
                        onCheckedChange = {}
                    )
                    Hint("想恢复自动更新，请在上面「数据来源」里切换到在线模式并登录。")
                } else {
                    SwitchRow(
                        title = "每天定时获取本周课表",
                        subtitle = "课表源服务器有时会关闭，建议设在上课前的时间",
                        checked = settings.autoFetch,
                        onCheckedChange = onAutoFetch
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingRow(
                        title = "获取时间",
                        subtitle = if (settings.autoFetch) "每天 ${settings.fetchTimeLabel} 自动更新" else "已关闭",
                        onClick = if (settings.autoFetch) ({ showFetchTime = true }) else null,
                        trailing = {
                            Text(
                                settings.fetchTimeLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
                        }
                    )
                    Hint("应用被系统清理后，定时任务会在下次开机或打开应用时自动恢复。")
                }
            }
        }

        // ---------------- 上课提醒
        item {
            SettingsCard("上课提醒") {
                SwitchRow(
                    title = "开启上课通知",
                    subtitle = "在课程开始前用系统通知提醒",
                    checked = settings.notifyEnabled,
                    onCheckedChange = onNotifyEnabled
                )
                if (settings.notifyEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("提前提醒时间", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 5, 10, 15, 30, 60).forEach { minute ->
                                FilterChip(
                                    selected = settings.notifyLeadMinutes == minute,
                                    onClick = { onNotifyLead(minute) },
                                    label = {
                                        Text(if (minute == 0) "准时" else "$minute 分钟")
                                    }
                                )
                            }
                        }
                    }
                    if (!notificationsAllowed) {
                        PermissionHint(
                            text = "还没有通知权限，提醒无法送达",
                            action = "去开启",
                            onAction = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.startActivity(
                                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                    )
                                }
                            }
                        )
                    }
                    if (!exactAlarmAllowed) {
                        PermissionHint(
                            text = "系统限制精确闹钟，提醒可能延迟几分钟",
                            action = "去允许",
                            onAction = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    runCatching {
                                        exactAlarmLauncher.launch(
                                            Intent(
                                                AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                "package:${context.packageName}".toUri()
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // ---------------- 节次时间
        item {
            SettingsCard("节次时间") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("校区作息", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CampusPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = settings.campus == preset,
                                onClick = { onCampus(preset) },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "切换校区会恢复该校区默认作息，可逐节修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                settings.sections.forEachIndexed { index, section ->
                    SectionTimeRow(
                        index = index,
                        section = section,
                        onClick = { editingSection = index }
                    )
                }
            }
        }

        // ---------------- 关于
        item {
            SettingsCard("关于") {
                SettingRow(title = "版本", subtitle = BuildConfig.VERSION_NAME)
                SettingRow(
                    title = "数据来源",
                    subtitle = if (settings.isLocal) "本地导入的课表 JSON"
                    else "仲恺农业工程学院教务系统（强智科技 jsxsd）"
                )
                Hint(
                    if (settings.isLocal) {
                        "本地模式下应用不联网，所有功能都在本机完成；课表只保存在本应用私有目录。"
                    } else {
                        "账号密码只保存在本机应用私有目录，用于课表更新与自动登录；应用不会上传任何数据。" +
                            "每次获取成功都会留一份课表快照，断网或服务器关闭时自动使用。"
                    }
                )
            }
        }
    }

    if (showFetchTime) {
        MinutePickerDialog(
            title = "每天自动获取时间",
            initialMinute = settings.fetchMinuteOfDay,
            onDismiss = { showFetchTime = false },
            onConfirm = {
                showFetchTime = false
                onFetchMinute(it)
            }
        )
    }

    editingSection?.let { index ->
        val current = settings.sections.getOrNull(index)
        if (current != null) {
            SectionTimeDialog(
                title = "第 ${index + 1} 节",
                initial = current,
                onDismiss = { editingSection = null },
                onConfirm = { start, end ->
                    editingSection = null
                    onSectionTime(index, start, end)
                }
            )
        }
    }

    if (showWeekDialog) {
        WeekPickerDialog(
            current = currentWeek,
            weekCount = maxOf(30, currentWeek),
            onDismiss = { showWeekDialog = false },
            onConfirm = {
                showWeekDialog = false
                onAdjustWeek(it)
            }
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun PermissionHint(text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Notifications,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SectionTimeRow(index: Int, section: SectionTime, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            section.rangeLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClick) {
            Icon(Icons.Default.Edit, contentDescription = "修改", modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinutePickerDialog(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimeInput(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTimeDialog(
    title: String,
    initial: SectionTime,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val startState = rememberTimePickerState(
        initialHour = initial.start / 60,
        initialMinute = initial.start % 60,
        is24Hour = true
    )
    val endState = rememberTimePickerState(
        initialHour = initial.end / 60,
        initialMinute = initial.end % 60,
        is24Hour = true
    )
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title 时间") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("开始", style = MaterialTheme.typography.labelMedium)
                TimeInput(state = startState)
                Spacer(Modifier.height(8.dp))
                Text("结束", style = MaterialTheme.typography.labelMedium)
                TimeInput(state = endState)
                if (error.isNotBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = startState.hour * 60 + startState.minute
                val e = endState.hour * 60 + endState.minute
                if (e <= s) {
                    error = "结束时间要晚于开始时间"
                } else {
                    onConfirm(s, e)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
