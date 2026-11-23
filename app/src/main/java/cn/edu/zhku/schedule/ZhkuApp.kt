package cn.edu.zhku.schedule

import android.app.Application
import cn.edu.zhku.schedule.data.Prefs
import cn.edu.zhku.schedule.notify.ClassNotifier
import cn.edu.zhku.schedule.work.DailyWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ZhkuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ClassNotifier(this).ensureChannel()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val settings = Prefs(this@ZhkuApp).settings()
                if (settings.autoFetch && !settings.isLocal) {
                    DailyWork.scheduleNext(this@ZhkuApp, settings.fetchMinuteOfDay)
                }
            }
        }
    }
}
