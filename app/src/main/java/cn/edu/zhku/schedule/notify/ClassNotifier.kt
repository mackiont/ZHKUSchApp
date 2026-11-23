package cn.edu.zhku.schedule.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.zhku.schedule.MainActivity
import cn.edu.zhku.schedule.R
import cn.edu.zhku.schedule.data.ClassSlot
import cn.edu.zhku.schedule.data.Prefs
import cn.edu.zhku.schedule.data.ScheduleMath
import cn.edu.zhku.schedule.data.SectionTime
import cn.edu.zhku.schedule.data.Timetable
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课提醒：用 AlarmManager 精确闹钟逐节课排程，比 WorkManager 准时。
 * 每次打开应用 / 获取课表 / 重启手机 / 改设置 都会重排未来 14 天的闹钟。
 */
class ClassNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "class_reminder"
        const val ACTION_CLASS_ALARM = "cn.edu.zhku.schedule.action.CLASS_ALARM"
        const val EXTRA_NAME = "course_name"
        const val EXTRA_ROOM = "course_room"
        const val EXTRA_TIME = "course_time"
        const val EXTRA_LEAD = "course_lead"
        private const val BASE_REQUEST = 4100
        private const val MAX_DAYS = 14
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "上课提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "在课程开始前提醒你"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 重排未来课程的上课提醒 */
    suspend fun reschedule(
        timetable: Timetable?,
        sections: List<SectionTime>,
        enabled: Boolean,
        leadMinutes: Int,
        prefs: Prefs
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val previous = prefs.alarmCount()
        for (i in 0 until previous) {
            alarmManager.cancel(pendingIntent(i, null, 0))
        }
        prefs.setAlarmCount(0)
        if (!enabled || timetable == null) return

        val now = LocalDateTime.now()
        val slots = ScheduleMath.upcoming(timetable, sections, now, MAX_DAYS)
        var index = 0
        for (slot in slots) {
            val trigger = slot.start.minusMinutes(leadMinutes.toLong())
            if (!trigger.isAfter(now)) continue
            val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            setAlarm(alarmManager, millis, pendingIntent(index, slot, leadMinutes))
            index++
            if (index >= 60) break // 上限，避免异常数据刷爆闹钟
        }
        prefs.setAlarmCount(index)
    }

    private fun pendingIntent(index: Int, slot: ClassSlot?, leadMinutes: Int): PendingIntent {
        val intent = Intent(context, ClassAlarmReceiver::class.java).apply {
            action = ACTION_CLASS_ALARM
            if (slot != null) {
                putExtra(EXTRA_NAME, slot.course.name)
                putExtra(EXTRA_ROOM, slot.course.place())
                putExtra(EXTRA_TIME, slot.rangeLabel)
                putExtra(EXTRA_LEAD, leadMinutes)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            BASE_REQUEST + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(manager: AlarmManager, millis: Long, pi: PendingIntent) {
        try {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun show(name: String, room: String, time: String, leadMinutes: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sub = listOf(time, room).filter { it.isNotBlank() }.joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle(name)
            .setContentText(
                if (leadMinutes > 0) "$sub（${leadMinutes} 分钟后上课）" else sub
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (leadMinutes > 0) "$sub\n${leadMinutes} 分钟后开始上课" else sub))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        runCatching {
            NotificationManagerCompat.from(context).notify(name.hashCode(), builder.build())
        }
    }

    fun canScheduleExactAlarms(): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }
}
