package cn.edu.zhku.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * 把小组件直接"钉"到桌面。
 *
 * 背景：部分国产桌面（MIUI / HyperOS 等）的小组件列表里不一定列出第三方应用，
 * 用户会以为应用不支持小组件。Android 8.0 提供了 requestPinAppWidget ——
 * 由系统直接弹「是否将仲恺课表小组件添加到桌面」，点确认就固定，不用在列表里翻。
 *
 * 长按应用图标弹出的「添加小组件」快捷方式、以及设置页的入口都走这里；
 * 桌面不支持时返回 false，由调用方给出图文引导。
 */
object WidgetPin {

    /** 当前桌面是否支持「请求固定小组件」 */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return manager.isRequestPinAppWidgetSupported
    }

    /**
     * 发起固定请求。系统会弹确认框，用户确认后才真正落到桌面。
     *
     * @return true 表示请求已发出；false 表示系统 / 桌面不支持，需要手动引导
     */
    fun request(context: Context): Boolean {
        if (!isSupported(context)) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val provider = ComponentName(context, ScheduleWidgetProvider::class.java)
        return runCatching {
            manager.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
    }
}
