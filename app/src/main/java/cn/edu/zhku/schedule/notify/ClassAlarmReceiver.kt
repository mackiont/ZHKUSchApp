package cn.edu.zhku.schedule.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 上课闹钟触发 → 发系统通知 */
class ClassAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClassNotifier.ACTION_CLASS_ALARM) return
        val name = intent.getStringExtra(ClassNotifier.EXTRA_NAME) ?: return
        val room = intent.getStringExtra(ClassNotifier.EXTRA_ROOM).orEmpty()
        val time = intent.getStringExtra(ClassNotifier.EXTRA_TIME).orEmpty()
        val lead = intent.getIntExtra(ClassNotifier.EXTRA_LEAD, 0)
        ClassNotifier(context.applicationContext).show(name, room, time, lead)
    }
}
