package cn.edu.zhku.schedule.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.edu.zhku.schedule.R
import cn.edu.zhku.schedule.ScheduleViewModel
import cn.edu.zhku.schedule.UiState
import cn.edu.zhku.schedule.data.LocalImport
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.data.WeekSource
import cn.edu.zhku.schedule.widget.WidgetPin
import kotlinx.coroutines.delay
import java.time.LocalDate

private const val ROUTE_HOME = "home"
private const val ROUTE_WEEK = "week"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun ZhkuRoot(
    viewModel: ScheduleViewModel,
    widgetRequest: Int = 0,
    onWidgetRequestHandled: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showWidgetGuide by remember { mutableStateOf(false) }

    // 请系统直接把小组件固定到桌面；桌面不支持时退回到图文引导
    val addWidget: () -> Unit = {
        if (WidgetPin.request(context)) {
            Toast.makeText(context, R.string.widget_pin_requested, Toast.LENGTH_SHORT).show()
        } else {
            showWidgetGuide = true
        }
    }

    // 长按图标的「添加小组件」快捷方式：进应用即弹系统的添加确认
    LaunchedEffect(widgetRequest) {
        if (widgetRequest > 0) {
            addWidget()
            onWidgetRequestHandled()
        }
    }

    LaunchedEffect(state.toast) {
        val message = state.toast
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeToast()
        }
    }

    // 导入：系统文件选择器，不需要任何存储权限
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    val openImport: () -> Unit = {
        runCatching {
            // 有的文件管理器不认 application/json，把 .json 置灰；
            // 带上 */* 保证任何来源的文件都能选到，内容不正确时解析层会报错
            importLauncher.launch(
                arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
            )
        }.onFailure {
            runCatching { importLauncher.launch(arrayOf("*/*")) }
        }
    }

    // 导出：系统另存为
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val openExport: () -> Unit = {
        val name = state.timetable?.let { LocalImport.suggestedFileName(it) } ?: "zhku-schedule.json"
        runCatching { exportLauncher.launch(name) }
    }

    when {
        !state.ready -> LoadingScreen()

        state.showLogin -> Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                LoginScreen(
                    busy = state.loggingIn,
                    importing = state.importing,
                    onLogin = { account, password -> viewModel.login(account, password) },
                    onImport = openImport,
                    onDismiss = if (state.timetable != null) ({ viewModel.dismissLogin() }) else null
                )
            }
        }

        else -> MainScaffold(
            state = state,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onImport = openImport,
            onExport = openExport
        )
    }

    state.pendingImport?.let { pending ->
        ImportPreviewDialog(
            pending = pending,
            onDismiss = { viewModel.cancelImport() },
            onConfirm = { week -> viewModel.confirmImport(week) }
        )
    }

    if (showWidgetGuide) {
        AddWidgetGuideDialog(onDismiss = { showWidgetGuide = false })
    }
}

@Composable
private fun AddWidgetGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.widget_guide_title)) },
        text = { Text(stringResource(R.string.widget_guide_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.widget_guide_ok))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    state: UiState,
    viewModel: ScheduleViewModel,
    snackbarHostState: SnackbarHostState,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: ROUTE_HOME

    val today = LocalDate.now()
    // 不停地重算：跨过周日 24:00 时，「第 N 周」在应用还开着的情况下也会自己 +1
    val week = rememberCurrentWeek(state.timetable)
    val title = when (route) {
        ROUTE_WEEK -> "本周课表"
        ROUTE_SETTINGS -> "设置"
        else -> "仲恺课表"
    }
    val subtitle = when (route) {
        ROUTE_WEEK -> null
        ROUTE_SETTINGS -> null
        else -> buildString {
            append("${today.monthValue}月${today.dayOfMonth}日 ")
            append(ScheduleMath.weekdayLabel(today.dayOfWeek.value))
            if (week != null) append(" · 第 $week 周")
            if (state.isLocal) append(" · 本地")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // 本地模式没有在线数据源，不显示刷新
                    if (route != ROUTE_SETTINGS && !state.isLocal) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新课表")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == ROUTE_HOME,
                    onClick = { navigateTo(navController, ROUTE_HOME) },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = route == ROUTE_WEEK,
                    onClick = { navigateTo(navController, ROUTE_WEEK) },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("课表") }
                )
                NavigationBarItem(
                    selected = route == ROUTE_SETTINGS,
                    onClick = { navigateTo(navController, ROUTE_SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    timetable = state.timetable,
                    sections = state.settings.sections,
                    refreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                    onOpenWeek = { navigateTo(navController, ROUTE_WEEK) },
                    banner = state.homeBanner,
                    bannerAction = if (!state.isLocal && state.offline) "重试" else null,
                    onBannerAction = { viewModel.refresh() }
                )
            }
            composable(ROUTE_WEEK) {
                WeekScreen(
                    timetable = state.timetable,
                    sections = state.settings.sections,
                    currentWeek = week ?: 1,
                    onAdjustWeek = { viewModel.adjustCurrentWeek(it) }
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    settings = state.settings,
                    currentWeek = week ?: 1,
                    weekSource = state.timetable?.weekSource ?: WeekSource.SERVER,
                    refreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                    onLogout = { viewModel.logout() },
                    onAutoFetch = { viewModel.setAutoFetch(it) },
                    onFetchMinute = { viewModel.setFetchMinute(it) },
                    onNotifyEnabled = { viewModel.setNotifyEnabled(it) },
                    onNotifyLead = { viewModel.setNotifyLead(it) },
                    onCampus = { viewModel.setCampus(it) },
                    onSectionTime = { index, start, end -> viewModel.setSectionTime(index, start, end) },
                    onRelogin = { viewModel.requestRelogin() },
                    onImport = onImport,
                    onExport = onExport,
                    onSwitchToOnline = { viewModel.switchToOnline() },
                    onAdjustWeek = { viewModel.adjustCurrentWeek(it) }
                )
            }
        }
    }

    if (state.needRelogin) {
        ReloginDialog(
            account = state.settings.account,
            busy = state.loggingIn,
            onDismiss = { viewModel.dismissRelogin() },
            onConfirm = { password -> viewModel.login(state.settings.account, password) }
        )
    }
}

private fun navigateTo(navController: androidx.navigation.NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * 当前教学周。每 60 秒重算一次，值变了才触发重组。
 * 这样周次不依赖任何定时任务，应用长时间挂在后台、或者离线放几周，
 * 回到前台周一那天都会自动对上。
 */
@Composable
private fun rememberCurrentWeek(timetable: Timetable?): Int? {
    val initial = timetable?.let { it.weekAt(LocalDate.now()) }
    val week by produceState(initial, timetable) {
        while (true) {
            value = timetable?.let { it.weekAt(LocalDate.now()) }
            delay(60_000L)
        }
    }
    return week
}
