package cn.edu.zhku.schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import cn.edu.zhku.schedule.MainActivity
import cn.edu.zhku.schedule.R
import cn.edu.zhku.schedule.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 桌面小组件的渲染与刷新调度。
 *
 * 倒计时只显示到分钟（`还有 1 小时 20 分`），所以刷新分两类闹钟：
 * 1. **状态切换**（上课 / 下课 / 跨天）—— [WidgetState.nextChange] 那一刻，用精确闹钟；
 * 2. **整分刷新** —— 有倒计时时才排，落在下一个整分，用普通闹钟（不进 Doze 白名单）。
 *
 * 两者共用一次 [refreshNow]：醒来后重算状态、重绘、再排下一次。
 * 屏幕亮着的时候普通闹钟基本准点；灭屏进 Doze 后会被合批，那时小组件也看不见，
 * 屏幕一亮就会补上，不值得为它每分钟唤醒一次 CPU。
 *
 * 另外 `updatePeriodMillis` 留了 30 分钟兜底，闹钟被系统清掉也不会一直停在旧状态。
 */
object ScheduleWidget {

    private const val RC_ALARM_STATE = 1001
    private const val RC_OPEN = 1002
    private const val RC_ALARM_TICK = 1003

    /** 数据变了（登录、抓取、导入、改节次、退出…）就调它，异步刷新，不阻塞调用方 */
    fun requestRefresh(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { refreshNow(app) }
        }
    }

    /** 立刻按当前课表重绘所有小组件，并排下一次刷新 */
    suspend fun refreshNow(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ScheduleWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) {
            cancelAlarm(context)
            return
        }

        val prefs = Prefs(context)
        val settings = prefs.settings()
        val timetable = prefs.loadTimetable()
        val now = LocalDateTime.now()
        val state = WidgetState.compute(timetable, settings.sections, now)

        // 每个实例按自己的真实尺寸缩放字号（同一张课表，不同桌面 / 不同尺寸可以不同）
        for (id in ids) {
            manager.updateAppWidget(id, buildViews(context, state, now, scaleFor(manager, id)))
        }
        scheduleAlarms(context, state, now)
    }

    /**
     * 把状态套到布局上。
     *
     * [scale] 是相对 110dp 基准盒子的整体缩放：字号、内边距一起放大，
     * 保证「课程名 : 状态行」这类比例恒定 —— 这正是 HyperOS 上字体失衡的根因。
     */
    internal fun buildViews(
        context: Context,
        state: WidgetState,
        now: LocalDateTime,
        scale: Float = 1f
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule)
        val s = scale.coerceIn(1f, 1.6f)

        views.setTextViewTextSize(R.id.widget_status, TypedValue.COMPLEX_UNIT_SP, 10f * s)
        views.setTextViewTextSize(R.id.widget_course, TypedValue.COMPLEX_UNIT_SP, 13f * s)
        views.setTextViewTextSize(R.id.widget_meta, TypedValue.COMPLEX_UNIT_SP, 9.5f * s)
        views.setTextViewTextSize(R.id.widget_countdown, TypedValue.COMPLEX_UNIT_SP, 11f * s)

        val padding = (11f * s * context.resources.displayMetrics.density).toInt()
        views.setViewPadding(R.id.widget_root, padding, padding, padding, padding)

        views.setTextViewText(R.id.widget_status, state.status)
        views.setTextViewText(R.id.widget_course, state.course)
        views.setTextViewText(R.id.widget_meta, state.meta)

        val countdown = state.countdownText(now)
        if (countdown == null) {
            views.setViewVisibility(R.id.widget_countdown, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_countdown, View.VISIBLE)
            views.setTextViewText(R.id.widget_countdown, countdown)
        }

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return views
    }

    /**
     * 按桌面实际给的尺寸算缩放。
     *
     * 2×2 在多数桌面是 110dp 左右，部分国产桌面（HyperOS 等）会放大到 150dp+；
     * 统一按短边与 110dp 的比值放大，最小 1.0（不小于基准，避免小盒子挤爆），最大 1.6。
     */
    private fun scaleFor(manager: AppWidgetManager, id: Int): Float {
        val options = manager.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val shortSide = minOf(width, height)
        if (shortSide <= 0) return 1f
        return (shortSide / 110f).coerceIn(1f, 1.6f)
    }

    private fun scheduleAlarms(context: Context, state: WidgetState, now: LocalDateTime) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val stateIntent = alarmIntent(context, RC_ALARM_STATE)
        val tickIntent = alarmIntent(context, RC_ALARM_TICK)
        alarmManager.cancel(stateIntent)
        alarmManager.cancel(tickIntent)

        val wallClock = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        // 状态切换：能精确就精确，只影响「上课中 / 下一节」切得准不准
        val nextChange = state.nextChange
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
        if (nextChange != null && nextChange > wallClock) {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextChange, stateIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextChange, stateIntent)
            }
        }

        // 整分刷新：只在有倒计时的时候排，普通 set 即可（不进 Doze 白名单）
        if (state.countdownTarget != null) {
            val nextTick = now.plusMinutes(1)
                .withSecond(0).withNano(0)
                .atZone(zone).toInstant().toEpochMilli()
            if (nextTick > wallClock) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextTick, tickIntent)
            }
        }
    }

    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(alarmIntent(context, RC_ALARM_STATE))
        alarmManager.cancel(alarmIntent(context, RC_ALARM_TICK))
    }

    private fun alarmIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, WidgetAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        RC_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
