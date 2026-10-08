package dev.waterctl.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.waterctl.app.domain.WaterViewModel
import dev.waterctl.app.ui.components.BleBlockReason
import dev.waterctl.app.ui.components.DevicePickerSheet
import dev.waterctl.app.ui.components.ErrorDialog
import dev.waterctl.app.ui.components.rememberBlePermissionState
import dev.waterctl.app.ui.screens.AboutScreen
import dev.waterctl.app.ui.screens.ControlScreen
import dev.waterctl.app.ui.screens.PermissionScreen
import dev.waterctl.app.ui.screens.RecordsScreen
import dev.waterctl.app.ui.screens.SettingsScreen

object Routes {
    const val CONTROL = "control"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private val TABS = listOf(Routes.CONTROL, Routes.RECORDS, Routes.SETTINGS)

/** 页面切换时长（毫秒）。默认值是 700，体感太慢。 */
private const val NAV_ENTER_MS = 180
private const val NAV_EXIT_MS = 130
private const val NAV_PUSH_MS = 220

@Composable
fun AppRoot(vm: WaterViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route ?: Routes.CONTROL
    val permission = rememberBlePermissionState()

    val showBottomBar = currentRoute in TABS

    // 权限就绪后，按设置尝试一次自动重连
    LaunchedEffect(permission.ready) {
        if (permission.ready) vm.tryAutoReconnect()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        // 各页自己处理状态栏内边距（AppTopBar 已带 statusBarsPadding），
        // 这里只让底部栏贡献内容区的下边距。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                AppNavigationBar(current = currentRoute) { target ->
                    navController.navigate(target) {
                        popUpTo(Routes.CONTROL) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.CONTROL,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = inner.calculateBottomPadding()),
            // navigation-compose 默认是 700ms 的淡入淡出，切个底部标签要等将近一秒，
            // 手感很拖沓。这里压到 180 / 130ms —— 快到几乎察觉不到，又不至于生硬。
            enterTransition = { fadeIn(tween(NAV_ENTER_MS)) },
            exitTransition = { fadeOut(tween(NAV_EXIT_MS)) },
            popEnterTransition = { fadeIn(tween(NAV_ENTER_MS)) },
            popExitTransition = { fadeOut(tween(NAV_EXIT_MS)) },
        ) {
            composable(Routes.CONTROL) {
                if (permission.reason == BleBlockReason.NONE) {
                    ControlScreen(
                        state = state.control,
                        onMainAction = vm::onMainAction,
                        onPickDevice = vm::openPicker,
                        onReconnect = vm::beginSession,
                    )
                } else {
                    PermissionScreen(
                        reason = permission.reason,
                        onRequest = permission.request,
                        onOpenAppSettings = permission.openAppSettings,
                        onOpenBluetoothSettings = permission.openBluetoothSettings,
                    )
                }
            }

            composable(Routes.RECORDS) {
                RecordsScreen(
                    state = state.records,
                    onGoToControl = {
                        navController.navigate(Routes.CONTROL) {
                            popUpTo(Routes.CONTROL) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = state.settings,
                    onRememberDeviceChange = vm::setRememberDevice,
                    onAutoReconnectChange = vm::setAutoReconnect,
                    onShowDebugLogChange = vm::setShowDebugLog,
                    onClearRecords = vm::clearRecords,
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }

            // 「关于」是二级页，用侧滑进出更符合层级直觉
            composable(
                Routes.ABOUT,
                enterTransition = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Left,
                        tween(NAV_PUSH_MS),
                    ) + fadeIn(tween(NAV_PUSH_MS))
                },
                exitTransition = { fadeOut(tween(NAV_EXIT_MS)) },
                popEnterTransition = { fadeIn(tween(NAV_EXIT_MS)) },
                popExitTransition = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.Right,
                        tween(NAV_PUSH_MS),
                    ) + fadeOut(tween(NAV_PUSH_MS))
                },
            ) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    state.error?.let { info ->
        ErrorDialog(
            info = info,
            debugLog = state.debugLog,
            showDebugLog = state.settings.showDebugLog,
            onDismiss = vm::dismissError,
        )
    }

    if (state.scanning) {
        DevicePickerSheet(
            scanning = state.scanning,
            devices = state.scanResults,
            onPick = vm::startWith,
            onDismiss = vm::closePicker,
        )
    }
}

/**
 * 底部导航 —— 直接使用 Material 3 的 NavigationBar：
 * 它的默认规格（高 80、指示器 64 × 32 圆角 16、图标 24、标签 12）与设计稿完全一致。
 */
@Composable
private fun AppNavigationBar(current: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavTab(
            selected = current == Routes.CONTROL,
            label = "水控",
            selectedIcon = Icons.Filled.WaterDrop,
            unselectedIcon = Icons.Outlined.WaterDrop,
            onClick = { onSelect(Routes.CONTROL) },
        )
        NavTab(
            selected = current == Routes.RECORDS,
            label = "记录",
            selectedIcon = Icons.Filled.History,
            unselectedIcon = Icons.Outlined.History,
            onClick = { onSelect(Routes.RECORDS) },
        )
        NavTab(
            selected = current == Routes.SETTINGS,
            label = "设置",
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
            onClick = { onSelect(Routes.SETTINGS) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTab(
    selected: Boolean,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
            )
        },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
