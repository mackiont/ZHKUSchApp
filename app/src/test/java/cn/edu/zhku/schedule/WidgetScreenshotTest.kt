package cn.edu.zhku.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.zhku.schedule.data.CampusPreset
import cn.edu.zhku.schedule.data.Course
import cn.edu.zhku.schedule.data.SectionTimes
import cn.edu.zhku.schedule.data.Timetable
import cn.edu.zhku.schedule.widget.ScheduleWidget
import cn.edu.zhku.schedule.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 把真实布局 inflate 出来渲染成 PNG，检查 2×2 正方形里的排版有没有被挤爆；
 * 顺便断言 RemoteViews 上的文字确实按状态填进去了。
 * 产物在 app/build/screenshots/widget*.png。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w200dp-h200dp-xxhdpi")
class WidgetScreenshotTest {

    private val sections = SectionTimes.preset(CampusPreset.BAIYUN)

    private val monday = LocalDateTime.of(2026, 11, 16, 8, 0)

    private val timetable = Timetable(
        courses = listOf(
            Course("高等数学II（1）", "席亚讲师", "经管楼209", "经管楼", "1-20(周)", (1..20).toList(), listOf(1, 2), "物联网264班", 1),
            Course("大学英语Ⅰ", "段国祥", "教学楼①402", "教学楼①", "1-20(周)", (1..20).toList(), listOf(6, 7), "物联网264班", 1)
        ),
        currentWeek = 12,
        fetchedAt = monday.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    private fun render(name: String): View {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = LocalDateTime.of(2026, 11, 16, 7, 20)
        val state = WidgetState.compute(timetable, sections, now)
        val views = ScheduleWidget.buildViews(context, state, now)
        val root = views.apply(context, FrameLayout(context))

        val size = (110 * context.resources.displayMetrics.density).toInt()
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, size, size)

        // 2×2 只有这么高，任何一行被挤出去都说明字号/行数改坏了
        for (id in listOf(R.id.widget_status, R.id.widget_course, R.id.widget_meta, R.id.widget_countdown)) {
            val child = root.findViewById<View>(id)
            assertTrue("视图 $id 被挤出小组件边界", child.bottom <= size && child.height > 0)
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFDCE4DE.toInt()) // 垫一层桌面底色，方便看圆角和描边
        root.draw(canvas)

        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return root
    }

    @Test
    fun widgetLight() {
        val root = render("widget")
        assertEquals("下一节课 · 今天", root.findViewById<TextView>(R.id.widget_status).text.toString())
        assertEquals("高等数学II（1）", root.findViewById<TextView>(R.id.widget_course).text.toString())
        assertEquals("08:40 · 经管楼209", root.findViewById<TextView>(R.id.widget_meta).text.toString())
        // Chronometer 未启动时也要有静态文案兜底（距 08:40 还有 1 小时 20 分）
        assertEquals("还有 1:20:00", root.findViewById<TextView>(R.id.widget_countdown).text.toString())
    }

    @Test
    @Config(qualifiers = "w200dp-h200dp-night-xxhdpi")
    fun widgetDark() {
        render("widget_dark")
    }
}
