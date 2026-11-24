package cn.edu.zhku.schedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 上课 / 下课 / 跨天时由 AlarmManager 唤醒，重绘小组件并排下一次 */
class WidgetAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ScheduleWidget.refreshNow(appContext)
            } catch (_: Exception) {
                // 下次 updatePeriodMillis 兜底刷新
            } finally {
                pending.finish()
            }
        }
    }
}
