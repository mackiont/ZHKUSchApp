package cn.edu.zhku.schedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 课程块配色：同一门课始终同色，方便在多处快速识别 */
private val CoursePalette = listOf(
    Color(0xFF2E7D4F),
    Color(0xFF1F6F8B),
    Color(0xFF8A5A2B),
    Color(0xFF6A4C93),
    Color(0xFF9C3D54),
    Color(0xFF3F612D),
    Color(0xFF475E8C),
    Color(0xFF8C6A1F)
)

fun courseColor(name: String): Color {
    if (name.isEmpty()) return CoursePalette[0]
    val idx = ((name.hashCode() % CoursePalette.size) + CoursePalette.size) % CoursePalette.size
    return CoursePalette[idx]
}

@Composable
fun courseContainerColor(name: String): Color {
    val base = courseColor(name)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return base.copy(alpha = if (dark) 0.30f else 0.14f)
}

private fun Color.luminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
