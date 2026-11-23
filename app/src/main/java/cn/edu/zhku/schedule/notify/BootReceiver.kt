package cn.edu.zhku.schedule.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.zhku.schedule.data.Prefs
import cn.edu.zhku.schedule.work.DailyWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新 / 系统时间变化后，闹钟与每日任务都会失效，需要重排。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED
            )
        ) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = Prefs(appContext)
                val settings = prefs.settings()
                val timetable = prefs.loadTimetable()
                ClassNotifier(appContext).apply {
                    ensureChannel()
                    reschedule(
                        timetable = timetable,
                        sections = settings.sections,
                        enabled = settings.notifyEnabled,
                        leadMinutes = settings.notifyLeadMinutes,
                        prefs = prefs
                    )
                }
                DailyWork.scheduleNext(appContext, settings.fetchMinuteOfDay)
            } catch (_: Exception) {
                // 开机广播里失败不重试，下次打开应用会重排
            } finally {
                pending.finish()
            }
        }
    }
}
