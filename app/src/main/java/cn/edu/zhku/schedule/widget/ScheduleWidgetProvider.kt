package cn.edu.zhku.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * 2×2 正方形小组件：显示最近一节课（正在上课就是当前这节）的课程、地点、节次时间与倒计时。
 * 点击打开应用。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        ScheduleWidget.requestRefresh(context)
    }

    override fun onEnabled(context: Context) {
        // 第一个小组件被添加：立刻渲染并排队刷新
        ScheduleWidget.requestRefresh(context)
    }

    override fun onDisabled(context: Context) {
        // 最后一个小组件被移除：别再为它设闹钟
        ScheduleWidget.cancelAlarm(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        ScheduleWidget.requestRefresh(context)
    }
}
