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
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.realme.budst300.BuildConfig
import com.realme.budst300.R
import com.realme.budst300.ble.BleConnectionState
import com.realme.budst300.protocol.T300Protocol
import com.realme.budst300.protocol.T300Protocol.AncMode
import com.realme.budst300.protocol.T300Protocol.TouchSide
import com.realme.budst300.protocol.T300Protocol.TouchGesture
import com.realme.budst300.protocol.T300Protocol.TouchAction
import com.realme.budst300.protocol.T300Protocol.getAvailableActions
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
                    primary = Color(0xFFFFCC00),     // Yellow Realme accent
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
            viewModel.refreshAll()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopDemoAudio()
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
    val isPlayingDemo by viewModel.isPlayingDemo.collectAsState()

    var selectedTouchSide by remember { mutableStateOf(TouchSide.LEFT) }
    var activeEditingGesture by remember { mutableStateOf<Pair<TouchSide, TouchGesture>?>(null) }
    val isT200 = detectedDevice?.name?.contains("T200", ignoreCase = true) == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detectedDevice?.name ?: stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (connectionState == BleConnectionState.CONNECTED) {
                        IconButton(onClick = { viewModel.refreshAll() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_state_desc))
                        }
                    }
                }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permissions warning banner
            if (!hasPermissions) {
                PermissionWarningCard(
                    onRequest = onRequestPermissions,
                    onOpenSettings = onOpenSettings
                )
            }

            // Device status card
            StatusDeviceCard(
                deviceStatus = deviceStatus,
                connectionState = connectionState,
                device = detectedDevice,
                onRetryConnect = { viewModel.manualReconnect() }
            )

            // Battery level section
            Text(
                text = stringResource(R.string.section_battery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BatteryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.battery_left),
                    battery = t300State.leftBattery,
                    icon = Icons.Default.Headphones
                )
                BatteryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.battery_case),
                    battery = t300State.caseBattery,
                    icon = Icons.Default.Inbox
                )
                BatteryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.battery_right),
                    battery = t300State.rightBattery,
                    icon = Icons.Default.Headphones
                )
            }

            // ANC Section
            Text(
                text = stringResource(R.string.section_anc),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            AncModeSelector(
                currentMode = t300State.ancMode,
                enabled = connectionState == BleConnectionState.CONNECTED,
                onModeSelected = { viewModel.setAncMode(it) }
            )

            // Sound Effects (EQ) Section
            Text(
                text = stringResource(R.string.section_eq),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            EqPresetSelector(
                currentPreset = t300State.eqPreset,
                activeCustomName = t300State.activeCustomEqName,
                enabled = connectionState == BleConnectionState.CONNECTED,
                onPresetSelected = { viewModel.setEqPreset(it) }
            )

            // Button Settings (Gestures) Section
            Text(
                text = stringResource(R.string.section_touch_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            ButtonSettingsCard(
                selectedSide = selectedTouchSide,
                touchSettings = t300State.touchSettings,
                enabled = connectionState == BleConnectionState.CONNECTED,
                onSideSelected = { selectedTouchSide = it },
                onGestureClick = { gesture ->
                    activeEditingGesture = Pair(selectedTouchSide, gesture)
                }
            )

            // Spatial Audio Section
            Text(
                text = stringResource(R.string.section_spatial_audio),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            SpatialAudioCard(
                spatialAudio = t300State.spatialAudio,
                isPlayingDemo = isPlayingDemo,
                enabled = connectionState == BleConnectionState.CONNECTED,
                onToggle = { viewModel.setSpatialAudio(it) },
                onToggleDemo = { viewModel.toggleDemoAudio() }
            )

            // High-Definition Audio (LDAC) — shown when supported (e.g. Buds T200)
            if (t300State.highQualityAudio != null || isT200) {
                SwitchFeatureCard(
                    title = stringResource(R.string.high_quality_audio_title),
                    description = stringResource(R.string.high_quality_audio_desc),
                    checked = t300State.highQualityAudio,
                    enabled = connectionState == BleConnectionState.CONNECTED,
                    onToggle = { viewModel.setHighQualityAudio(it) }
                )
            }

            // Dynamic Audio — shown when supported (e.g. Buds T200)
            if (t300State.dynamicAudio != null || isT200) {
                SwitchFeatureCard(
                    title = stringResource(R.string.dynamic_audio_title),
                    description = stringResource(R.string.dynamic_audio_desc),
                    checked = t300State.dynamicAudio,
                    enabled = connectionState == BleConnectionState.CONNECTED,
                    onToggle = { viewModel.setDynamicAudio(it) }
                )
            }

            // Other - Game Mode & Dual-Device Connection Section
            Text(
                text = stringResource(R.string.section_other),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            GameModeCard(
                gameMode = t300State.gameMode,
                enabled = connectionState == BleConnectionState.CONNECTED,
                onToggle = { viewModel.setGameMode(it) }
            )

            // Dual-Device Connection — shown when supported (e.g. Buds T200)
            if (t300State.dualConnection != null || isT200) {
                DualConnectionCard(
                    dualConnection = t300State.dualConnection,
                    connectedDevices = t300State.connectedDevices,
                    enabled = connectionState == BleConnectionState.CONNECTED,
                    onToggle = { viewModel.setDualConnection(it) }
                )
            }

            // Logs Console
            LogsConsole(
                logs = logs,
                onClearLogs = { viewModel.clearLogs() },
                modifier = Modifier.fillMaxWidth()
            )

            // Build version at the very bottom
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "build ${BuildConfig.VERSION_CODE}",
                fontSize = 11.sp,
                color = Color.DarkGray,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
            )
        }

        // Gesture Action Selection Dialog
        if (activeEditingGesture != null) {
            val (side, gesture) = activeEditingGesture!!
            val currentAction = t300State.touchSettings[Pair(side, gesture)]
            GestureActionSelectionDialog(
                side = side,
                gesture = gesture,
                currentAction = currentAction,
                onActionSelected = { action ->
                    viewModel.setKeySetting(side, gesture, action)
                    activeEditingGesture = null
                },
                onDismiss = { activeEditingGesture = null }
            )
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
                    text = stringResource(R.string.perm_warning_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFCC00),
                    fontSize = 15.sp
                )
            }
            Text(
                text = stringResource(R.string.perm_warning_message),
                fontSize = 13.sp,
                color = Color.LightGray
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00))
                ) {
                    Text(stringResource(R.string.btn_grant_permissions), color = Color.Black)
                }
                OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.btn_settings), color = Color.White)
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
                        text = device?.name ?: stringResource(R.string.default_device_name),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = device?.address ?: stringResource(R.string.status_searching_paired),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                // Status indicator
                val (badgeText, badgeColor) = when {
                    connectionState == BleConnectionState.CONNECTED -> stringResource(R.string.status_connected) to Color(0xFF4CAF50)
                    connectionState == BleConnectionState.CONNECTING -> stringResource(R.string.status_connecting) to Color(0xFFFF9800)
                    deviceStatus == SystemDeviceStatus.DEVICE_CONNECTED_OS -> stringResource(R.string.status_online_connecting) to Color(0xFFFF9800)
                    deviceStatus == SystemDeviceStatus.NO_PAIRED_DEVICE -> stringResource(R.string.status_not_paired) to Color(0xFFE53935)
                    deviceStatus == SystemDeviceStatus.NO_PERMISSIONS -> stringResource(R.string.status_no_permissions) to Color(0xFFE53935)
                    else -> stringResource(R.string.status_in_case_disconnected) to Color.Gray
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

            // Status hint
            val hintText = when {
                connectionState == BleConnectionState.CONNECTED -> stringResource(R.string.hint_link_active)
                connectionState == BleConnectionState.CONNECTING -> stringResource(R.string.hint_connecting)
                deviceStatus == SystemDeviceStatus.DEVICE_DISCONNECTED -> stringResource(R.string.hint_disconnected)
                deviceStatus == SystemDeviceStatus.NO_PAIRED_DEVICE -> stringResource(R.string.hint_not_paired)
                deviceStatus == SystemDeviceStatus.NO_PERMISSIONS -> stringResource(R.string.hint_no_permissions)
                else -> stringResource(R.string.hint_waiting_activity)
            }

            Text(text = hintText, fontSize = 12.sp, color = Color(0xFFAAAAAA))

            // Manual reconnect button when disconnected but OS-connected
            if (device != null && connectionState == BleConnectionState.DISCONNECTED && deviceStatus == SystemDeviceStatus.DEVICE_CONNECTED_OS) {
                Button(
                    onClick = onRetryConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_reconnect), color = MaterialTheme.colorScheme.onPrimary)
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
                    text = if (battery.level >= 0) "${battery.level}%" else stringResource(R.string.dash),
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
                        text = stringResource(mode.titleRes),
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
fun EqPresetSelector(
    currentPreset: T300Protocol.EqPreset?,
    activeCustomName: String?,
    enabled: Boolean,
    onPresetSelected: (T300Protocol.EqPreset) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(
                T300Protocol.EqPreset.SERENADE,
                T300Protocol.EqPreset.ORIGINAL_SOUND,
                T300Protocol.EqPreset.PURE_BASS,
                T300Protocol.EqPreset.DEEP_BASS
            )

            for (row in presets.chunked(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (preset in row) {
                        val selected = (preset == currentPreset) && (activeCustomName == null)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2A2A2A))
                                .clickable(enabled = enabled) { onPresetSelected(preset) }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(preset.titleRes),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else if (enabled) Color.White else Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            if (activeCustomName != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.eq_custom_active, activeCustomName),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ButtonSettingsCard(
    selectedSide: TouchSide,
    touchSettings: Map<Pair<TouchSide, TouchGesture>, TouchAction>,
    enabled: Boolean,
    onSideSelected: (TouchSide) -> Unit,
    onGestureClick: (TouchGesture) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Earbud selector (Left / Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A2A2A))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TouchSide.entries.forEach { side ->
                    val isSelected = (side == selectedSide)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable(enabled = enabled) { onSideSelected(side) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(if (side == TouchSide.LEFT) R.string.touch_side_left_earbud else R.string.touch_side_right_earbud),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else if (enabled) Color.White else Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // List of 3 gestures
            val gestures = listOf(
                TouchGesture.DOUBLE_TAP,
                TouchGesture.TRIPLE_TAP,
                TouchGesture.LONG_PRESS
            )

            gestures.forEachIndexed { index, gesture ->
                val action = touchSettings[Pair(selectedSide, gesture)]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onGestureClick(gesture) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(gesture.titleRes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (enabled) Color.White else Color.Gray
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (action != null) stringResource(action.titleRes) else stringResource(R.string.dash),
                            fontSize = 12.sp,
                            color = when {
                                !enabled -> Color.DarkGray
                                action != null -> MaterialTheme.colorScheme.primary
                                else -> Color.Gray
                            }
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (enabled) Color.Gray else Color(0xFF3A3A3A),
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (index < gestures.lastIndex) {
                    HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun GestureActionSelectionDialog(
    side: TouchSide,
    gesture: TouchGesture,
    currentAction: TouchAction?,
    onActionSelected: (TouchAction) -> Unit,
    onDismiss: () -> Unit
) {
    val allowedActions = remember(gesture, currentAction) {
        val list = getAvailableActions(gesture).toMutableList()
        if (currentAction != null && currentAction !in list) {
            list.add(0, currentAction)
        }
        list
    }

    val sideName = stringResource(if (side == TouchSide.LEFT) R.string.touch_side_left_earbud else R.string.touch_side_right_earbud)
    val gestureName = stringResource(gesture.titleRes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dialog_gesture_title, sideName, gestureName),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                allowedActions.forEach { action ->
                    val isSelected = (action == currentAction)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onActionSelected(action) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onActionSelected(action) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(action.titleRes),
                            fontSize = 14.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = Color(0xFF242424)
    )
}

@Composable
fun SpatialAudioCard(
    spatialAudio: Boolean?,
    isPlayingDemo: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleDemo: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.section_spatial_audio),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.spatial_audio_desc),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // Options: [ Off ] [ On ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A2A2A))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val options = listOf(
                    false to stringResource(R.string.spatial_audio_off),
                    true to stringResource(R.string.spatial_audio_on)
                )
                options.forEach { (value, label) ->
                    val isSelected = (spatialAudio == value)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable(enabled = enabled && spatialAudio != null) { onToggle(value) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else if (enabled) Color.White else Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 0.5.dp)

            // Demo sound button from realme Link
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isPlayingDemo) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0xFF222222))
                    .clickable { onToggleDemo() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPlayingDemo) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isPlayingDemo) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isPlayingDemo) stringResource(R.string.demo_audio_playing) else stringResource(R.string.demo_audio_play),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isPlayingDemo) MaterialTheme.colorScheme.primary else Color.White
                        )
                        Text(
                            text = if (spatialAudio == true) stringResource(R.string.demo_sample_spatial) else stringResource(R.string.demo_sample_stereo),
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                if (isPlayingDemo) {
                    Text(
                        text = stringResource(R.string.demo_audio_stop),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun GameModeCard(
    gameMode: Boolean?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    SwitchFeatureCard(
        title = stringResource(R.string.game_mode_title),
        description = stringResource(R.string.game_mode_desc),
        checked = gameMode,
        enabled = enabled,
        onToggle = onToggle
    )
}

@Composable
fun SwitchFeatureCard(
    title: String,
    description: String,
    checked: Boolean?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            Switch(
                checked = checked ?: false,
                enabled = enabled && checked != null,
                onCheckedChange = { onToggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0xFF2C2C2C)
                )
            )
        }
    }
}

@Composable
fun DualConnectionCard(
    dualConnection: Boolean?,
    connectedDevices: List<T300Protocol.ConnectedDeviceInfo>,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.dual_connection_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = stringResource(R.string.dual_connection_desc),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = dualConnection ?: false,
                    enabled = enabled && dualConnection != null,
                    onCheckedChange = { onToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF2C2C2C)
                    )
                )
            }

            if (connectedDevices.isNotEmpty()) {
                HorizontalDivider(color = Color(0xFF2C2C2C), thickness = 0.5.dp)
                Text(
                    text = stringResource(R.string.dual_connection_history),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    connectedDevices.forEach { dev ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = if (dev.isConnected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = dev.name.ifBlank { dev.mac },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                    Text(
                                        text = dev.mac,
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            Text(
                                text = stringResource(
                                    if (dev.isConnected) R.string.dual_connection_device_connected
                                    else R.string.dual_connection_device_disconnected
                                ),
                                fontSize = 12.sp,
                                color = if (dev.isConnected) Color(0xFF4CAF50) else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsConsole(
    logs: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom of the log list
    LaunchedEffect(logs.size, isExpanded) {
        if (isExpanded && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Logs header and controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.logs_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${logs.size})",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isExpanded) {
                        // Copy button
                        IconButton(
                            onClick = {
                                if (logs.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.logs_copied_toast, logs.size),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.logs_copy_desc),
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Clear button
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.logs_clear_desc),
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Collapse / Expand button
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) stringResource(R.string.logs_collapse_desc) else stringResource(R.string.logs_expand_desc),
                            tint = Color.LightGray
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.logs_empty), color = Color.DarkGray, fontSize = 12.sp)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF050505))
                                .padding(8.dp)
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(logs) { log ->
                                    Text(
                                        text = log,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp,
                                        color = when {
                                            log.contains("RX") -> Color(0xFF81C784)
                                            log.contains("TX") -> Color(0xFF64B5F6)
                                            log.contains("✓") -> Color(0xFFFFD54F)
                                            log.contains("Error") || log.contains("unavailable") || log.contains("Ошибка") || log.contains("недоступны") -> Color(0xFFE57373)
                                            else -> Color(0xFFB0BEC5)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
