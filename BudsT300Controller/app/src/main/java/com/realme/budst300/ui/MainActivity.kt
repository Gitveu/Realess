package com.realme.budst300.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.realme.budst300.ble.BleConnectionState
import com.realme.budst300.protocol.T300Protocol
import com.realme.budst300.protocol.T300Protocol.AncMode
import com.realme.budst300.viewmodel.BudsViewModel
import com.realme.budst300.viewmodel.SystemDeviceStatus

class MainActivity : ComponentActivity() {

    private val viewModel: BudsViewModel by viewModels()

    private val requiredPermissions: Array<String> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFCC00),     // Жёлтый акцент Realme
                    onPrimary = Color.Black,
                    surface = Color(0xFF1E1E1E),
                    background = Color(0xFF121212)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestPermissions = { checkAndRequestPermissions() },
                        onOpenSettings = { openAppSettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions(this, requiredPermissions)) {
            viewModel.onPermissionsGranted()
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasAllPermissions(this, requiredPermissions)) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    companion object {
        fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
            return permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: BudsViewModel,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val t300State by viewModel.t300State.collectAsState()
    val deviceStatus by viewModel.deviceStatus.collectAsState()
    val detectedDevice by viewModel.detectedBudsDevice.collectAsState()
    val logs by viewModel.recentLogs.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Realme Buds T300", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (connectionState == BleConnectionState.CONNECTED) {
                        IconButton(onClick = {
                            viewModel.refreshAncState()
                            viewModel.refreshBattery()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить состояние")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Баннер отсутствия разрешений (если пользователь их ещё не дал)
            if (!hasPermissions) {
                PermissionWarningCard(
                    onRequest = onRequestPermissions,
                    onOpenSettings = onOpenSettings
                )
            }

            // Автоматическая карточка устройства и статуса
            StatusDeviceCard(
                deviceStatus = deviceStatus,
                connectionState = connectionState,
                device = detectedDevice,
                onRetryConnect = { viewModel.manualReconnect() }
            )

            // Секция уровня заряда
            Text(
                text = "Уровень заряда",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BatteryCard(
                    modifier = Modifier.weight(1f),
                    title = "Левый",
                    battery = t300State.leftBattery,
                    icon = Icons.Default.Headphones
                )
                BatteryCard(
                    modifier = Modifier.weight(1f),
                    title = "Кейс",
                    battery = t300State.caseBattery,
                    icon = Icons.Default.Inbox
                )
                BatteryCard(
                    modifier = Modifier.weight(1f),
                    title = "Правый",
                    battery = t300State.rightBattery,
                    icon = Icons.Default.Headphones
                )
            }

            // Секция переключения режимов шумоподавления (ANC)
            Text(
                text = "Шумоподавление",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            AncModeSelector(
                currentMode = t300State.ancMode,
                enabled = connectionState == BleConnectionState.CONNECTED,
                onModeSelected = { viewModel.setAncMode(it) }
            )

            // Консоль логов для живого наблюдения за протоколом
            LogsConsole(logs = logs, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun PermissionWarningCard(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF332000)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFCC00))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Требуется доступ к Bluetooth",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFCC00),
                    fontSize = 15.sp
                )
            }
            Text(
                text = "Приложению необходимы разрешения «Устройства поблизости» (BLUETOOTH_CONNECT), чтобы автоматически управлять наушниками.",
                fontSize = 13.sp,
                color = Color.LightGray
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00))
                ) {
                    Text("Разрешить доступ", color = Color.Black)
                }
                OutlinedButton(onClick = onOpenSettings) {
                    Text("Настройки", color = Color.White)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun StatusDeviceCard(
    deviceStatus: SystemDeviceStatus,
    connectionState: BleConnectionState,
    device: BluetoothDevice?,
    onRetryConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = device?.name ?: "realme Buds T300",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = device?.address ?: "Поиск сопряжённого устройства...",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                // Индикатор состояния
                val (badgeText, badgeColor) = when {
                    connectionState == BleConnectionState.CONNECTED -> "Подключено" to Color(0xFF4CAF50)
                    connectionState == BleConnectionState.CONNECTING -> "Подключение..." to Color(0xFFFF9800)
                    deviceStatus == SystemDeviceStatus.DEVICE_CONNECTED_OS -> "В сети (подключение)" to Color(0xFFFF9800)
                    deviceStatus == SystemDeviceStatus.NO_PAIRED_DEVICE -> "Не сопряжено" to Color(0xFFE53935)
                    deviceStatus == SystemDeviceStatus.NO_PERMISSIONS -> "Нет прав" to Color(0xFFE53935)
                    else -> "В кейсе / отключено" to Color.Gray
                }

                Badge(containerColor = badgeColor.copy(alpha = 0.2f)) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Пояснение статуса
            val hintText = when {
                connectionState == BleConnectionState.CONNECTED -> "✓ Канал прямого управления Buds T300 активен."
                connectionState == BleConnectionState.CONNECTING -> "Установка соединения с наушниками через SPP/GATT..."
                deviceStatus == SystemDeviceStatus.DEVICE_DISCONNECTED -> "Наушники не подключены к телефону по Bluetooth. Откройте кейс или наденьте наушники."
                deviceStatus == SystemDeviceStatus.NO_PAIRED_DEVICE -> "Buds T300 не найдены среди сопряжённых устройств в Android. Выполните сопряжение в настройках телефона."
                deviceStatus == SystemDeviceStatus.NO_PERMISSIONS -> "Предоставьте доступ к Bluetooth выше."
                else -> "Ожидание активности устройства..."
            }

            Text(text = hintText, fontSize = 12.sp, color = Color(0xFFAAAAAA))

            // Если наушники в сети, но сокет завис или отключился — возможность принудительного реконнекта
            if (device != null && connectionState == BleConnectionState.DISCONNECTED && deviceStatus == SystemDeviceStatus.DEVICE_CONNECTED_OS) {
                Button(
                    onClick = onRetryConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Повторить подключение к наушникам", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
fun BatteryCard(
    modifier: Modifier = Modifier,
    title: String,
    battery: T300Protocol.BatteryInfo,
    icon: ImageVector
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (battery.level >= 0) "${battery.level}%" else "—",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (battery.isCharging) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("⚡", color = Color(0xFFFFCC00), fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (battery.level >= 0) battery.level / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = when {
                    battery.isCharging -> Color(0xFFFFCC00)
                    battery.level in 0..19 -> Color(0xFFE53935)
                    else -> Color(0xFF4CAF50)
                },
                trackColor = Color(0xFF2C2C2C)
            )
        }
    }
}

@Composable
fun AncModeSelector(
    currentMode: AncMode?,
    enabled: Boolean,
    onModeSelected: (AncMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AncMode.entries.forEach { mode ->
                val selected = mode == currentMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable(enabled = enabled) { onModeSelected(mode) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.title,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else if (enabled) Color.White else Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LogsConsole(logs: List<String>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Протокольный лог (SPP / GATT live):", fontSize = 11.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = log,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            log.contains("RX") -> Color(0xFF81C784)
                            log.contains("TX") -> Color(0xFF64B5F6)
                            log.contains("✓") -> Color(0xFFFFD54F)
                            log.contains("Ошибка") || log.contains("недоступны") -> Color(0xFFE57373)
                            else -> Color(0xFFB0BEC5)
                        }
                    )
                }
            }
        }
    }
}
