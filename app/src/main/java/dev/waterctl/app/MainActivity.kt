package dev.waterctl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.waterctl.app.domain.WaterViewModel
import dev.waterctl.app.ui.AppRoot
import dev.waterctl.app.ui.theme.WaterCtlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 设计稿的状态栏只是占位示意，真机上用系统真实的状态栏 / 导航栏，
        // 页面内容靠 statusBarsPadding 与底部栏的 windowInsets 让开。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            WaterCtlTheme {
                val vm: WaterViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}
