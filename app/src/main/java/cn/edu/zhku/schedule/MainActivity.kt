package cn.edu.zhku.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import cn.edu.zhku.schedule.ui.ZhkuRoot
import cn.edu.zhku.schedule.ui.theme.ZhkuTheme

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * 长按应用图标的「添加小组件」快捷方式会带这个 action 启动。
         * 部分桌面（如小米）的小组件列表里找不到第三方应用，从这里可以绕过列表。
         */
        const val ACTION_ADD_WIDGET = "cn.edu.zhku.schedule.action.ADD_WIDGET"
    }

    private val viewModel: ScheduleViewModel by viewModels()

    /** 「添加小组件」请求序号，自增；Compose 侧处理完归零，避免重组重复触发 */
    private val widgetRequests = mutableIntStateOf(0)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不影响主流程，设置页会实时显示状态 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 只在首次创建时消费：旋转 / 重建时 intent 还在，不能重复弹窗
        if (savedInstanceState == null) consumeWidgetRequest(intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZhkuTheme {
                ZhkuRoot(
                    viewModel = viewModel,
                    widgetRequest = widgetRequests.intValue,
                    onWidgetRequestHandled = { widgetRequests.intValue = 0 }
                )
            }
        }
        askNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWidgetRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    private fun consumeWidgetRequest(intent: Intent?) {
        if (intent?.action == ACTION_ADD_WIDGET) {
            widgetRequests.intValue++
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
