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

    private fun render(name: String, sizeDp: Int = 110, scale: Float = 1f): View {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = LocalDateTime.of(2026, 11, 16, 7, 20)
        val state = WidgetState.compute(timetable, sections, now)
        val views = ScheduleWidget.buildViews(context, state, now, scale)
        val root = views.apply(context, FrameLayout(context))

        val size = (sizeDp * context.resources.displayMetrics.density).toInt()
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
        // 倒计时只到分钟（距 08:40 还有 1 小时 20 分）
        val countdown = root.findViewById<TextView>(R.id.widget_countdown)
        assertEquals("还有 1 小时 20 分", countdown.text.toString())
        // 文案必须整行放下，被截断意味着用户看不到完整的倒计时
        assertEquals("倒计时被截断", countdown.text.length, countdown.layout.getLineEnd(0))
    }

    /**
     * 国产桌面（HyperOS 等）会把 2×2 渲染得比 110dp 大。
     * 字号可以跟着盒子放大，但**比例必须恒定** —— 1.4.0 上课程名被 autoSize
     * 撑到把状态行衬得像脚注，就是比例失控。
     */
    @Test
    fun fontSizeScalesButKeepsTheSameRatio() {
        val small = render("widget_scale_1", sizeDp = 110, scale = 1f)
        val large = render("widget_large", sizeDp = 160, scale = 160 / 110f)

        fun ratio(root: View): Float =
            root.findViewById<TextView>(R.id.widget_course).textSize /
                root.findViewById<TextView>(R.id.widget_status).textSize

        // 13sp / 10sp，两个尺寸下都该是 1.3
        assertEquals(1.3f, ratio(small), 0.02f)
        assertEquals(1.3f, ratio(large), 0.02f)
        assertTrue(
            "放大后字号应当真的变大",
            large.findViewById<TextView>(R.id.widget_course).textSize >
                small.findViewById<TextView>(R.id.widget_course).textSize
        )
    }

    @Test
    @Config(qualifiers = "w200dp-h200dp-night-xxhdpi")
    fun widgetDark() {
        render("widget_dark")
    }
}
