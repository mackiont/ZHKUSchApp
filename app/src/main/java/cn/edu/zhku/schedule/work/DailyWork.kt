package cn.edu.zhku.schedule.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.edu.zhku.schedule.data.Prefs
import cn.edu.zhku.schedule.data.ScheduleRepository
import cn.edu.zhku.schedule.notify.ClassNotifier
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 每天固定时间自动获取课表。
 *
 * 用"一次性任务 + 自己排下一次"而不是 PeriodicWorkRequest：
 * PeriodicWorkRequest 只能保证间隔，不能保证落在指定的钟点上。
 * 任务链在下一次任务结束时继续往后排，所以只要执行一次就永远接得上；
 * WorkManager 的任务本身会被系统持久化，重启手机后依然有效。
 */
object DailyWork {

    const val UNIQUE_NAME = "zhku_daily_task"

    fun scheduleNext(context: Context, minuteOfDay: Int) {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(1000L)

        val request = OneTimeWorkRequestBuilder<DailyFetchWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}

/**
 * 每日任务：按需抓课表 + 重排上课提醒。
 * 即使关闭了自动获取，也会保留这个任务来续排提醒（课表已经缓存在本地）。
 */
class DailyFetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val settings = prefs.settings()
        var failed = false

        // 本地模式（导入的 JSON）不联网，只续排上课提醒
        if (settings.autoFetch && !settings.isLocal && settings.account.isNotBlank()) {
            try {
                ScheduleRepository(applicationContext, prefs).refresh()
            } catch (_: Exception) {
                failed = true
            }
        }

        val latest = prefs.settings()
        val timetable = prefs.loadTimetable()
        ClassNotifier(applicationContext).apply {
            ensureChannel()
            reschedule(
                timetable = timetable,
                sections = latest.sections,
                enabled = latest.notifyEnabled,
                leadMinutes = latest.notifyLeadMinutes,
                prefs = prefs
            )
        }

        // 服务器偶尔会关闭：失败时让 WorkManager 退避重试（30 分钟起，指数增长）
        if (failed && runAttemptCount < 2) {
            return Result.retry()
        }

        DailyWork.scheduleNext(applicationContext, latest.fetchMinuteOfDay)
        return Result.success()
    }
}
