package dev.waterctl.app.ui.components

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 权限缺失的原因。
 * - Android 12+ 是「附近的设备」（BLUETOOTH_SCAN / BLUETOOTH_CONNECT）
 * - Android 11 及以下扫描 BLE 走「位置信息」（ACCESS_FINE_LOCATION）
 */
enum class BleBlockReason { NONE, NO_BLUETOOTH, BLUETOOTH_OFF, PERMISSION_DENIED }

fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Stable
class BlePermissionState(
    val reason: BleBlockReason,
    val request: () -> Unit,
    val openAppSettings: () -> Unit,
    val openBluetoothSettings: () -> Unit,
) {
    val ready: Boolean get() = reason == BleBlockReason.NONE
}

@Composable
fun rememberBlePermissionState(): BlePermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(hasBlePermissions(context)) }
    var adapterOn by remember { mutableStateOf(isBluetoothOn(context)) }
    var hasAdapter by remember { mutableStateOf(hasBluetoothAdapter(context)) }

    fun refresh() {
        granted = hasBlePermissions(context)
        adapterOn = isBluetoothOn(context)
        hasAdapter = hasBluetoothAdapter(context)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    // 从系统设置返回时重新评估（用户可能在设置里改了权限或开了蓝牙）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val reason = when {
        !hasAdapter -> BleBlockReason.NO_BLUETOOTH
        !granted -> BleBlockReason.PERMISSION_DENIED
        !adapterOn -> BleBlockReason.BLUETOOTH_OFF
        else -> BleBlockReason.NONE
    }

    return remember(reason) {
        BlePermissionState(
            reason = reason,
            request = { launcher.launch(requiredBlePermissions()) },
            openAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
            openBluetoothSettings = {
                context.startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
        )
    }
}

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

private fun hasBluetoothAdapter(context: Context): Boolean = bluetoothAdapter(context) != null

@Suppress("MissingPermission")
private fun isBluetoothOn(context: Context): Boolean =
    runCatching { bluetoothAdapter(context)?.isEnabled == true }.getOrDefault(false)
