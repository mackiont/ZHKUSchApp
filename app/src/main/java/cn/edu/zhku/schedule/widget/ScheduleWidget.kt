package cn.edu.zhku.schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import cn.edu.zhku.schedule.MainActivity
import cn.edu.zhku.schedule.R
import cn.edu.zhku.schedule.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 桌面小组件的渲染与刷新调度。
 *
 * 「准时」靠两件事配合：
 * 1. **倒计时**用 [android.widget.Chronometer]（`setChronometer` + countDown），它由桌面进程自己
 *    按秒刷新，不需要我们定期唤醒 —— 睡觉、锁屏都不会让数字停住。
 * 2. **状态切换**（下一节 → 上课中 → 再下一节、跨天）只在 [WidgetState.nextChange] 那一刻用
 *    精确闹钟唤醒一次，改完再排下一次。所以一天最多响几次，而不是每分钟。
 *
 * 另外 `updatePeriodMillis` 留了 30 分钟兜底，闹钟被系统清掉也不会一直停在旧状态。
 */
object ScheduleWidget {

    private const val RC_ALARM = 1001
    private const val RC_OPEN = 1002

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

        manager.updateAppWidget(ids, buildViews(context, state, now))
        scheduleNextAlarm(context, state.nextChange)
    }

    /** 把状态套到布局上。时钟基准用 elapsedRealtime，重启后由开机广播重排 */
    internal fun buildViews(context: Context, state: WidgetState, now: LocalDateTime): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule)
        views.setTextViewText(R.id.widget_status, state.status)
        views.setTextViewText(R.id.widget_course, state.course)
        views.setTextViewText(R.id.widget_meta, state.meta)

        val target = state.countdownTarget
        val format = state.countdownFormat
        if (target == null || format == null) {
            views.setViewVisibility(R.id.widget_countdown, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_countdown, View.VISIBLE)
            val remain = Duration.between(now, target).toMillis().coerceAtLeast(0L)
            // 先写一份静态文案：万一桌面没有把 Chronometer 跑起来，也不会留一行空白
            views.setTextViewText(R.id.widget_countdown, format.format(elapsedLabel(remain / 1000)))
            views.setChronometer(
                R.id.widget_countdown,
                SystemClock.elapsedRealtime() + remain,
                format,
                true
            )
            views.setChronometerCountDown(R.id.widget_countdown, true)
        }

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return views
    }

    fun scheduleNextAlarm(context: Context, next: LocalDateTime?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        alarmManager.cancel(pending)
        if (next == null) return

        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) return

        // Android 14 起 SCHEDULE_EXACT_ALARM 默认不授予；没有权限就退化成非精确，
        // 误差只影响「上课中/下一节」的切换，倒计时数字本身仍然准（Chronometer 自己走）
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }

    /** 与 Chronometer 内部一致的时间格式：超过一小时才带小时位 */
    private fun elapsedLabel(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_ALARM,
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
