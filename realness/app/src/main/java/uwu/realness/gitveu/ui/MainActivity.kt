package uwu.realness.gitveu.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import uwu.realness.gitveu.BuildConfig
import uwu.realness.gitveu.R
import uwu.realness.gitveu.RealnessApp
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.protocol.HuaweiProtocol
import uwu.realness.gitveu.protocol.NothingProtocol
import uwu.realness.gitveu.protocol.BudsProtocol
import uwu.realness.gitveu.protocol.BudsProtocol.AncMode
import uwu.realness.gitveu.protocol.BudsProtocol.TouchAction
import uwu.realness.gitveu.protocol.BudsProtocol.TouchGesture
import uwu.realness.gitveu.protocol.BudsProtocol.TouchSide
import uwu.realness.gitveu.protocol.BudsProtocol.getAvailableActions
import uwu.realness.gitveu.viewmodel.BudsViewModel
import uwu.realness.gitveu.viewmodel.RenameScope
import uwu.realness.gitveu.viewmodel.SystemDeviceStatus
import uwu.realness.gitveu.widget.WidgetStateStore
import kotlin.math.roundToInt

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

    private var pendingCdmRename: String? = null
    private var pendingEnablePopupAfterGrant: Boolean = false
    private val systemStateRevision = mutableIntStateOf(0)

    private val cdmAssociationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingCdmRename
        pendingCdmRename = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            val scope = viewModel.completeOsAliasRename(pending)
            val toastRes = if (scope == RenameScope.OS_ALIAS) R.string.rename_toast_os else R.string.rename_toast_local
            Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestCompanionAssociationAndRename(device: BluetoothDevice, newName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, getString(R.string.rename_toast_local), Toast.LENGTH_SHORT).show()
            return
        }
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            Toast.makeText(this, getString(R.string.rename_toast_local), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCdmRename = newName
        try {
            val filter = BluetoothDeviceFilter.Builder()
                .setAddress(device.address)
                .build()
            val request = AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build()

            val callback = object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    runOnUiThread {
                        try {
                            cdmAssociationLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                        } catch (_: Throwable) {
                            Toast.makeText(this@MainActivity, getString(R.string.rename_toast_local), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onAssociationPending(intentSender: IntentSender) {
                    runOnUiThread {
                        try {
                            cdmAssociationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        } catch (_: Throwable) {
                            Toast.makeText(this@MainActivity, getString(R.string.rename_toast_local), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    val p = pendingCdmRename
                    pendingCdmRename = null
                    if (p != null) {
                        runOnUiThread {
                            val scope = viewModel.completeOsAliasRename(p)
                            val toastRes = if (scope == RenameScope.OS_ALIAS) R.string.rename_toast_os else R.string.rename_toast_local
                            Toast.makeText(this@MainActivity, getString(toastRes), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    pendingCdmRename = null
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, getString(R.string.rename_toast_local), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(request, mainExecutor, callback)
            } else {
                cdm.associate(request, callback, null)
            }
            Toast.makeText(this, getString(R.string.rename_toast_cdm_prompt), Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            pendingCdmRename = null
            Toast.makeText(this, getString(R.string.rename_toast_local), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            RealnessExpressiveTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        systemStateRevision = systemStateRevision.intValue,
                        onRequestPermissions = { checkAndRequestPermissions() },
                        onOpenSettings = { openAppSettings() },
                        onOpenOverlaySettings = { enableAfterGrant ->
                            pendingEnablePopupAfterGrant = enableAfterGrant
                            openOverlayPermissionSettings()
                        },
                        onOpenBatterySettings = { openBatteryOptimizationSettings() },
                        onOpenUrl = { url -> openExternalUrl(url) },
                        onRequestCdmRename = { dev, name -> requestCompanionAssociationAndRename(dev, name) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val popupMgr = RealnessApp.from(this).popupManager
        if (pendingEnablePopupAfterGrant) {
            pendingEnablePopupAfterGrant = false
            if (popupMgr.canDrawOverlays()) {
                popupMgr.setEnabled(true)
                Toast.makeText(this, getString(R.string.settings_popup_enabled_toast), Toast.LENGTH_SHORT).show()
            }
        }
        systemStateRevision.intValue++
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

    private fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            openAppSettings()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (_: Throwable) {
            openAppSettings()
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {}
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
    systemStateRevision: Int,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOverlaySettings: (enableAfterGrant: Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRequestCdmRename: (BluetoothDevice, String) -> Unit
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val budsState by viewModel.budsState.collectAsState()
    val deviceStatus by viewModel.deviceStatus.collectAsState()
    val detectedDevice by viewModel.detectedBudsDevice.collectAsState()
    val usbHeadsetInfo by viewModel.usbHeadsetInfo.collectAsState()
    val detectedModel by viewModel.detectedModel.collectAsState()
    val autoDetectedModel by viewModel.autoDetectedModel.collectAsState()
    val manualModelOverride by viewModel.manualModelOverride.collectAsState()
    val lastDeviceName by viewModel.lastDeviceName.collectAsState()
    val lastDeviceMac by viewModel.lastDeviceMac.collectAsState()
    val customDeviceAlias by viewModel.customDeviceAlias.collectAsState()
    val lastCaseBattery by viewModel.lastCaseBattery.collectAsState()
    val unsupportedDeviceName by viewModel.unsupportedDeviceName.collectAsState()
    val logs by viewModel.recentLogs.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val isPlayingDemo by viewModel.isPlayingDemo.collectAsState()
    val findIsPlaying by viewModel.findIsPlaying.collectAsState()
    val findVolumeProgress by viewModel.findVolumeProgress.collectAsState()
    val findChannel by viewModel.findChannel.collectAsState()

    var selectedTouchSide by remember { mutableStateOf(TouchSide.LEFT) }
    var activeEditingGesture by remember { mutableStateOf<Pair<TouchSide, TouchGesture>?>(null) }

    var showCustomEqSheet by remember { mutableStateOf(false) }
    var editingCustomProfile by remember { mutableStateOf<BudsProtocol.CustomEqProfile?>(null) }
    var creatingNewCustomProfile by remember { mutableStateOf(false) }
    var showFindBudsSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val rawDeviceName = usbHeadsetInfo?.displayName ?: detectedDevice?.name ?: lastDeviceName
    val displayDeviceName = customDeviceAlias ?: rawDeviceName
    val displayDeviceMac = usbHeadsetInfo?.usbId ?: detectedDevice?.address ?: lastDeviceMac
    val isConnected = connectionState == BleConnectionState.CONNECTED
    val hasKnownDevice = displayDeviceMac != null || detectedDevice != null || usbHeadsetInfo != null

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModelPickerDrawerContent(
                autoDetectedModel = autoDetectedModel,
                manualModelOverride = manualModelOverride,
                systemStateRevision = systemStateRevision,
                onSelectModel = { chosen ->
                    viewModel.selectManualModel(chosen)
                    drawerScope.launch { drawerState.close() }
                },
                onOpenOverlaySettings = onOpenOverlaySettings,
                onOpenBatterySettings = onOpenBatterySettings,
                onOpenAppSettings = onOpenSettings,
                onOpenUrl = onOpenUrl,
                onPreviewPopup = {
                    val popupMgr = RealnessApp.from(context).popupManager
                    if (!popupMgr.canDrawOverlays()) {
                        onOpenOverlaySettings(true)
                    } else {
                        drawerScope.launch {
                            drawerState.close()
                            kotlinx.coroutines.delay(140)
                            popupMgr.showPreview(
                                deviceName = displayDeviceName,
                                model = detectedModel,
                                currentState = budsState,
                                connected = isConnected
                            )
                        }
                    }
                }
            )
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = stringResource(R.string.drawer_menu_desc)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = displayDeviceName ?: stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    actions = {
                        if (isConnected) {
                            IconButton(onClick = { viewModel.refreshAll() }) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.refresh_state_desc)
                                )
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Permissions warning banner (skip when USB dongle is connected)
                if (!hasPermissions && usbHeadsetInfo == null) {
                    PermissionWarningCard(
                        onRequest = onRequestPermissions,
                        onOpenSettings = onOpenSettings
                    )
                }

                // Logitech USB-OTG permission prompt
                if (usbHeadsetInfo != null && !usbHeadsetInfo!!.hasPermission) {
                    UsbPermissionCard(
                        deviceName = usbHeadsetInfo!!.displayName,
                        onGrant = { viewModel.requestUsbPermission() }
                    )
                }

                // Unsupported connected Bluetooth audio device banner
                if (deviceStatus == SystemDeviceStatus.UNSUPPORTED_DEVICE && unsupportedDeviceName != null) {
                    UnsupportedDeviceCard(deviceName = unsupportedDeviceName!!)
                }

                // Expressive Hero Device Card with Quick Actions (Find earbuds + Rename)
                StatusDeviceCard(
                    deviceStatus = deviceStatus,
                    connectionState = connectionState,
                    device = detectedDevice,
                    displayName = displayDeviceName,
                    displayMac = displayDeviceMac,
                    manualModelOverride = manualModelOverride,
                    unsupportedDeviceName = unsupportedDeviceName,
                    findIsPlaying = findIsPlaying || budsState.hardwareFindActive,
                    canRename = hasKnownDevice,
                    onRetryConnect = { viewModel.manualReconnect() },
                    onOpenFindBuds = { showFindBudsSheet = true },
                    onOpenRename = { showRenameDialog = true }
                )

                // Battery section
                val isStandardBtMode = detectedModel.isGenericBluetooth || budsState.isStandardBluetoothFallback
                SectionHeader(title = stringResource(R.string.section_battery))
                if (detectedModel.isSingleBatteryHeadset || isStandardBtMode) {
                    SingleHeadsetBatteryCard(
                        battery = budsState.leftBattery,
                        titleRes = if (isStandardBtMode) R.string.battery_overall else R.string.battery_headset,
                        voltageMv = budsState.batteryVoltageMv,
                        hoursLeft = budsState.batteryHoursLeft
                    )
                    if (isStandardBtMode && isConnected) {
                        StandardBluetoothModeCard()
                    }
                } else {
                    val cachedCaseSubtitle = if (budsState.caseBattery.level < 0 && lastCaseBattery != null) {
                        WidgetStateStore.formatRelativeTime(context, lastCaseBattery!!.second)
                    } else null
                    val effectiveCaseBattery = if (budsState.caseBattery.level >= 0) {
                        budsState.caseBattery
                    } else if (lastCaseBattery != null) {
                        BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.CASE, lastCaseBattery!!.first, false)
                    } else {
                        budsState.caseBattery
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BatteryCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.battery_left),
                            battery = budsState.leftBattery,
                            icon = Icons.Rounded.Headphones
                        )
                        BatteryCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.battery_case),
                            battery = effectiveCaseBattery,
                            icon = Icons.Rounded.Inventory2,
                            subtitle = cachedCaseSubtitle,
                            isCached = cachedCaseSubtitle != null
                        )
                        BatteryCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.battery_right),
                            battery = budsState.rightBattery,
                            icon = Icons.Rounded.Headphones
                        )
                    }
                }

                if (!isStandardBtMode) {
                // Logitech G733 / G435 Controls (Sidetone, RGB lights, Auto power-off)
                if (detectedModel.supportsSidetone || detectedModel.supportsRgbLights || detectedModel.supportsInactiveTime) {
                    SectionHeader(title = stringResource(R.string.section_logitech))
                    LogitechG733ControlsCard(
                        sidetoneLevel = budsState.sidetoneLevel,
                        lightsEnabled = budsState.lightsEnabled,
                        inactiveMinutes = budsState.inactiveMinutes,
                        enabled = isConnected,
                        onSidetoneChanged = { viewModel.setSidetone(it) },
                        onLightsToggle = { viewModel.setLights(it) },
                        onInactiveSelected = { viewModel.setInactiveTime(it) }
                    )
                }

                // Noise Cancellation (ANC) Section
                if (!detectedModel.isSupported || detectedModel.supportsAnc) {
                    SectionHeader(title = stringResource(R.string.section_anc))
                    AncModeSelector(
                        currentMode = budsState.ancMode,
                        currentLevel = budsState.ancLevel,
                        supportsAncLevel = detectedModel.supportsAncLevel,
                        supportsAncDynamic = detectedModel.supportsAncDynamic,
                        galaxyAdaptiveOnly = detectedModel.isGalaxy,
                        useLowMidHighLabels = detectedModel.isNothing || detectedModel.isXiaomi || detectedModel.isRealme || detectedModel.isBose || detectedModel.isSoundcore || detectedModel.isGalaxy,
                        enabled = isConnected,
                        onModeSelected = { viewModel.setAncMode(it) },
                        onLevelSelected = { viewModel.setAncLevel(it) }
                    )
                }

                // Sound Effects (EQ) + Protocol-Aware Custom Equalizer Section
                val customEqSpec = detectedModel.customEqSpec()
                if (!detectedModel.isSupported || detectedModel.supportsEq || customEqSpec != null) {
                    SectionHeader(title = stringResource(R.string.section_eq))
                    EqPresetSelector(
                        currentPreset = budsState.eqPreset,
                        activeCustomName = budsState.activeCustomEqName,
                        activeCustomId = budsState.activeCustomEqId,
                        customProfiles = budsState.customEqProfiles,
                        customEqSpec = customEqSpec,
                        showPresets = !detectedModel.isSupported || (detectedModel.supportsEq && !detectedModel.isBose && !detectedModel.isSoundcore && !detectedModel.isSennheiser && !detectedModel.isPixelBuds),
                        model = detectedModel,
                        enabled = isConnected,
                        onPresetSelected = { viewModel.setEqPreset(it) },
                        onSelectCustomProfile = { viewModel.selectCustomEqProfile(it) },
                        onEditCustomProfile = { profile ->
                            editingCustomProfile = profile
                            creatingNewCustomProfile = false
                            showCustomEqSheet = true
                        },
                        onDeleteCustomProfile = { viewModel.deleteCustomEqProfile(it) },
                        onCreateCustomProfile = {
                            editingCustomProfile = null
                            creatingNewCustomProfile = true
                            showCustomEqSheet = true
                        },
                        onOpenDirectCustomEq = {
                            editingCustomProfile = budsState.customEqProfiles.firstOrNull { it.isSelected }
                                ?: budsState.customEqProfiles.firstOrNull()
                            creatingNewCustomProfile = false
                            showCustomEqSheet = true
                        }
                    )
                }

                // Button Settings (Gestures) Section
                if (!detectedModel.isSupported || detectedModel.supportsTouchSettings) {
                    SectionHeader(title = stringResource(R.string.section_touch_settings))
                    val visibleGestures = buildList {
                        if (!detectedModel.isSupported || detectedModel.supportsDoubleTap) add(TouchGesture.DOUBLE_TAP)
                        if (!detectedModel.isSupported || detectedModel.supportsTripleTap) add(TouchGesture.TRIPLE_TAP)
                        if (!detectedModel.isSupported || detectedModel.supportsLongPress) add(TouchGesture.LONG_PRESS)
                    }
                    ButtonSettingsCard(
                        selectedSide = selectedTouchSide,
                        gestures = visibleGestures,
                        touchSettings = budsState.touchSettings,
                        enabled = isConnected,
                        onSideSelected = { selectedTouchSide = it },
                        onGestureClick = { gesture ->
                            activeEditingGesture = Pair(selectedTouchSide, gesture)
                        }
                    )
                }

                // Spatial Audio Section
                if (!detectedModel.isSupported || detectedModel.supportsSpatialAudio) {
                    SectionHeader(title = stringResource(R.string.section_spatial_audio))
                    SpatialAudioCard(
                        spatialAudio = budsState.spatialAudio,
                        spatialAudioMode = budsState.spatialAudioMode,
                        nothingSpatialModes = detectedModel.nothingSpatialModes,
                        isPlayingDemo = isPlayingDemo,
                        showRealmeDemo = !detectedModel.isSupported || detectedModel.isRealme,
                        allowToggleWhenUnknown = detectedModel.isSoundcore || detectedModel.isSennheiser || detectedModel.isJbl,
                        enabled = isConnected,
                        onToggle = { viewModel.setSpatialAudio(it) },
                        onSelectNothingMode = { viewModel.setSpatialAudioMode(it) },
                        onToggleDemo = { viewModel.toggleDemoAudio() }
                    )
                }

                // High-Definition Audio (LDAC / LHDC 5.0 / Huawei Sound Quality Priority)
                if (detectedModel.supportsHighQualityAudio) {
                    val codecDescRes = when {
                        detectedModel.isHuawei -> R.string.high_quality_audio_desc_huawei
                        detectedModel.usesLhdc -> R.string.high_quality_audio_desc_lhdc
                        else -> R.string.high_quality_audio_desc
                    }
                    SwitchFeatureCard(
                        title = stringResource(R.string.high_quality_audio_title),
                        description = stringResource(codecDescRes),
                        icon = Icons.Rounded.HighQuality,
                        checked = budsState.highQualityAudio,
                        enabled = isConnected,
                        onToggle = { viewModel.setHighQualityAudio(it) }
                    )
                }

                // Dynamic Audio (realme) / Enhanced Bass with 1..5 level selector (Nothing & CMF)
                if (detectedModel.supportsDynamicAudio) {
                    if (detectedModel.supportsBassLevel) {
                        BassBoostLevelCard(
                            enabled = isConnected,
                            checked = budsState.dynamicAudio,
                            level = budsState.bassLevel,
                            onToggle = { viewModel.setDynamicAudio(it) },
                            onLevelSelected = { viewModel.setBassLevel(it) }
                        )
                    } else {
                        SwitchFeatureCard(
                            title = stringResource(R.string.dynamic_audio_title),
                            description = stringResource(R.string.dynamic_audio_desc),
                            icon = Icons.Rounded.Equalizer,
                            checked = budsState.dynamicAudio,
                            enabled = isConnected,
                            onToggle = { viewModel.setDynamicAudio(it) }
                        )
                    }
                }

                // Other Section: Game Mode / Low Latency, Dual Connection, Auto Play/Pause, Auto Answer
                val hasOtherSection = !detectedModel.isSupported ||
                    detectedModel.supportsGameMode ||
                    detectedModel.supportsDualConnection ||
                    detectedModel.supportsAutoPlayPause ||
                    detectedModel.supportsAutoAnswer
                if (hasOtherSection) {
                    SectionHeader(title = stringResource(R.string.section_other))

                    if (!detectedModel.isSupported || detectedModel.supportsGameMode) {
                        val useLowLatencyLabel = detectedModel.isXiaomi || detectedModel.isHuawei || detectedModel.isNothing
                        SwitchFeatureCard(
                            title = stringResource(if (useLowLatencyLabel) R.string.low_latency_title else R.string.game_mode_title),
                            description = stringResource(if (useLowLatencyLabel) R.string.low_latency_desc else R.string.game_mode_desc),
                            icon = Icons.Rounded.SportsEsports,
                            checked = budsState.gameMode,
                            enabled = isConnected,
                            allowToggleWhenUnknown = detectedModel.isXiaomi || detectedModel.isGalaxy || detectedModel.isSoundcore,
                            onToggle = { viewModel.setGameMode(it) }
                        )
                    }

                    if (detectedModel.supportsDualConnection) {
                        if (detectedModel.isGalaxy) {
                            SwitchFeatureCard(
                                title = stringResource(R.string.dual_connection_galaxy_title),
                                description = stringResource(R.string.dual_connection_galaxy_desc),
                                icon = Icons.Rounded.Devices,
                                checked = budsState.dualConnection,
                                enabled = isConnected,
                                onToggle = { viewModel.setDualConnection(it) }
                            )
                        } else {
                            DualConnectionCard(
                                dualConnection = budsState.dualConnection,
                                connectedDevices = budsState.connectedDevices,
                                enabled = isConnected,
                                onToggle = { viewModel.setDualConnection(it) }
                            )
                        }
                    }

                    if (detectedModel.supportsAutoPlayPause) {
                        SwitchFeatureCard(
                            title = stringResource(R.string.auto_play_pause_title),
                            description = stringResource(R.string.auto_play_pause_desc),
                            icon = Icons.Rounded.Earbuds,
                            checked = budsState.autoPlayPause,
                            enabled = isConnected,
                            onToggle = { viewModel.setAutoPlayPause(it) }
                        )
                    }

                    if (detectedModel.supportsAutoAnswer) {
                        SwitchFeatureCard(
                            title = stringResource(R.string.auto_answer_title),
                            description = stringResource(R.string.auto_answer_desc),
                            icon = Icons.Rounded.Call,
                            checked = budsState.autoAnswer,
                            enabled = isConnected,
                            onToggle = { viewModel.setAutoAnswer(it) }
                        )
                    }
                }

                // Total Usage Time Statistics (L / R)
                if (budsState.leftUseTimeMinutes != null || budsState.rightUseTimeMinutes != null) {
                    UseTimeCard(
                        leftMinutes = budsState.leftUseTimeMinutes,
                        rightMinutes = budsState.rightUseTimeMinutes
                    )
                }
                }

                // Protocol Logs Console
                LogsConsole(
                    logs = logs,
                    onClearLogs = { viewModel.clearLogs() },
                    modifier = Modifier.fillMaxWidth()
                )

                // Footer Version
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                )
            }

            // Gesture Action Selection Dialog
            if (activeEditingGesture != null) {
                val (side, gesture) = activeEditingGesture!!
                val currentAction = budsState.touchSettings[Pair(side, gesture)]
                GestureActionSelectionDialog(
                    model = detectedModel,
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

            // Custom Equalizer ModalBottomSheet
            val activeSpec = detectedModel.customEqSpec()
            if (showCustomEqSheet && activeSpec != null) {
                CustomEqEditorSheet(
                    spec = activeSpec,
                    initialGains = budsState.customEqGains,
                    editingProfile = editingCustomProfile,
                    isCreatingNew = creatingNewCustomProfile,
                    onDismiss = { showCustomEqSheet = false },
                    onApply = { gains, name ->
                        viewModel.saveCustomEq(
                            gains = gains,
                            profileId = if (creatingNewCustomProfile) null else editingCustomProfile?.id,
                            profileName = name,
                            createNew = creatingNewCustomProfile
                        )
                        showCustomEqSheet = false
                        Toast.makeText(context, context.getString(R.string.eq_custom_saved_toast), Toast.LENGTH_SHORT).show()
                    },
                    onReset = {
                        viewModel.resetCustomEq()
                    }
                )
            }

            // Universal Find My Earbuds ModalBottomSheet
            if (showFindBudsSheet) {
                FindBudsSheet(
                    isPlaying = findIsPlaying,
                    volumeProgress = findVolumeProgress,
                    selectedChannel = findChannel,
                    supportsHardwareRing = detectedModel.supportsHardwareFindBuds,
                    hardwareRingActive = budsState.hardwareFindActive,
                    isConnected = isConnected,
                    onChannelSelected = { viewModel.setFindChannel(it) },
                    onTogglePlay = {
                        if (findIsPlaying) viewModel.stopFindBuds() else viewModel.startFindBuds()
                    },
                    onToggleHardwareRing = { enabled ->
                        viewModel.setHardwareFindBuds(enabled)
                    },
                    onDismiss = { showFindBudsSheet = false }
                )
            }

            // Rename Earbuds Dialog
            if (showRenameDialog) {
                RenameDeviceDialog(
                    currentName = displayDeviceName ?: "",
                    onConfirm = { newName ->
                        val scope = viewModel.renameCurrentDevice(newName)
                        showRenameDialog = false
                        if (scope == RenameScope.NEEDS_CDM_ASSOCIATION && detectedDevice != null) {
                            onRequestCdmRename(detectedDevice!!, newName.trim())
                        } else {
                            val toastRes = when (scope) {
                                RenameScope.FIRMWARE -> R.string.rename_toast_firmware
                                RenameScope.OS_ALIAS -> R.string.rename_toast_os
                                RenameScope.LOCAL_ALIAS -> R.string.rename_toast_local
                                RenameScope.RESET -> R.string.rename_toast_reset
                                else -> null
                            }
                            if (toastRes != null) {
                                Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDismiss = { showRenameDialog = false }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerDrawerContent(
    autoDetectedModel: BudsProtocol.BudsModel,
    manualModelOverride: BudsProtocol.BudsModel?,
    systemStateRevision: Int,
    onSelectModel: (BudsProtocol.BudsModel?) -> Unit,
    onOpenOverlaySettings: (enableAfterGrant: Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onPreviewPopup: () -> Unit
) {
    val context = LocalContext.current
    val popupManager = remember(context) { RealnessApp.from(context).popupManager }
    val grouped = remember { BudsProtocol.BudsModel.modelsByBrand }
    val isAutoSelected = manualModelOverride == null
    var selectedTab by remember { mutableIntStateOf(0) }

    // Re-read system permissions & popup preferences whenever activity resumes
    val canDrawOverlays = remember(systemStateRevision) { popupManager.canDrawOverlays() }
    val isBatteryUnrestricted = remember(systemStateRevision) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Throwable) {
            false
        }
    }
    var popupEnabled by remember(systemStateRevision) { mutableStateOf(popupManager.isEnabled()) }
    var popupCompact by remember(systemStateRevision) { mutableStateOf(popupManager.isCompactMode()) }
    var popupShowAnc by remember(systemStateRevision) { mutableStateOf(popupManager.showAncControls()) }
    var popupTimeoutSec by remember(systemStateRevision) { mutableIntStateOf(popupManager.autoDismissSeconds()) }

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(
                    if (selectedTab == 0) R.string.drawer_title_model else R.string.drawer_tab_settings
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp, start = 4.dp)
            )

            // Segmented tabs: Models | Settings & About
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.drawer_tab_models),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.drawer_tab_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedTab == 0) {
                // "Automatic" top card
                val autoSubtitle = if (autoDetectedModel.isSupported) {
                    stringResource(R.string.drawer_auto_detected, autoDetectedModel.displayName)
                } else {
                    stringResource(R.string.drawer_auto_not_connected)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelectModel(null) },
                    color = if (isAutoSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.drawer_auto_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isAutoSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = autoSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAutoSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isAutoSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    grouped.forEach { (brand, models) ->
                        item(key = "brand_${brand.name}") {
                            Text(
                                text = brand.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp)
                            )
                        }
                        items(models, key = { it.name }) { model ->
                            val selected = manualModelOverride == model
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape)
                                    .clickable { onSelectModel(model) },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                shape = CircleShape
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // General Settings & About tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Section 1: Connection Popup
                    Text(
                        text = stringResource(R.string.settings_popup_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_popup_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.settings_popup_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = popupEnabled && canDrawOverlays,
                                    onCheckedChange = { wantEnable ->
                                        if (wantEnable) {
                                            if (canDrawOverlays) {
                                                popupEnabled = true
                                                popupManager.setEnabled(true)
                                            } else {
                                                onOpenOverlaySettings(true)
                                            }
                                        } else {
                                            popupEnabled = false
                                            popupManager.setEnabled(false)
                                        }
                                    },
                                    thumbContent = if (popupEnabled && canDrawOverlays) {
                                        {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize)
                                            )
                                        }
                                    } else null
                                )
                            }

                            if (!canDrawOverlays) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_popup_perm_needed),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        FilledTonalButton(
                                            onClick = { onOpenOverlaySettings(true) },
                                            shape = CircleShape,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Layers,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.settings_popup_perm_grant))
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )

                            // Compact popup mode (name + status + battery only)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_popup_compact_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_popup_compact_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = popupCompact,
                                    onCheckedChange = { checked ->
                                        popupCompact = checked
                                        popupManager.setCompactMode(checked)
                                    }
                                )
                            }

                            // Show ANC pills inside popup (hidden in compact mode)
                            AnimatedVisibility(visible = !popupCompact) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 10.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_popup_anc_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_popup_anc_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = popupShowAnc,
                                        onCheckedChange = { checked ->
                                            popupShowAnc = checked
                                            popupManager.setShowAncControls(checked)
                                        }
                                    )
                                }
                            }

                            // Auto-dismiss timeout selector (4s / 6s / 10s)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_popup_timeout_title),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val timeoutOptions = listOf(4, 6, 10)
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    timeoutOptions.forEachIndexed { idx, sec ->
                                        SegmentedButton(
                                            selected = popupTimeoutSec == sec,
                                            onClick = {
                                                popupTimeoutSec = sec
                                                popupManager.setAutoDismissSeconds(sec)
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = idx,
                                                count = timeoutOptions.size
                                            )
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_popup_timeout_sec, sec),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFeatureSettings = "tnum"
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            // Live preview button
                            FilledTonalButton(
                                onClick = onPreviewPopup,
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_popup_preview))
                            }
                        }
                    }

                    // Section 2: System & Background
                    Text(
                        text = stringResource(R.string.settings_system_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DrawerActionRow(
                                icon = if (isBatteryUnrestricted) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryAlert,
                                title = stringResource(R.string.settings_battery_title),
                                subtitle = stringResource(
                                    if (isBatteryUnrestricted) R.string.settings_battery_unrestricted
                                    else R.string.settings_battery_restricted
                                ),
                                highlightIcon = isBatteryUnrestricted,
                                onClick = onOpenBatterySettings
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            DrawerActionRow(
                                icon = Icons.Rounded.Settings,
                                title = stringResource(R.string.settings_app_info_title),
                                subtitle = stringResource(R.string.settings_app_info_desc),
                                onClick = onOpenAppSettings
                            )
                        }
                    }

                    // Section 3: About
                    Text(
                        text = stringResource(R.string.settings_about_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Headphones,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.settings_about_version,
                                            BuildConfig.VERSION_NAME,
                                            BuildConfig.VERSION_CODE
                                        ),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.settings_about_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            DrawerLinkRow(
                                icon = Icons.Rounded.Code,
                                title = stringResource(R.string.settings_about_github),
                                onClick = { onOpenUrl("https://github.com/Gitveu/Realess") }
                            )
                            DrawerLinkRow(
                                icon = Icons.Rounded.DevicesOther,
                                title = stringResource(R.string.settings_about_devices),
                                onClick = {
                                    onOpenUrl("https://github.com/Gitveu/Realess/blob/main/SUPPORTED_DEVICES.md")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlightIcon: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (highlightIcon) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlightIcon) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DrawerLinkRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun PermissionWarningCard(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.perm_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = stringResource(R.string.perm_warning_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequest, shape = CircleShape) {
                    Text(stringResource(R.string.btn_grant_permissions))
                }
                OutlinedButton(onClick = onOpenSettings, shape = CircleShape) {
                    Text(stringResource(R.string.btn_settings))
                }
            }
        }
    }
}

@Composable
fun UsbPermissionCard(deviceName: String, onGrant: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.usb_perm_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = stringResource(R.string.usb_perm_message, deviceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            Button(onClick = onGrant, shape = CircleShape) {
                Text(stringResource(R.string.btn_grant_usb))
            }
        }
    }
}

@Composable
fun UnsupportedDeviceCard(deviceName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.unsupported_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = stringResource(R.string.unsupported_warning_message, deviceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun StatusDeviceCard(
    deviceStatus: SystemDeviceStatus,
    connectionState: BleConnectionState,
    device: BluetoothDevice?,
    displayName: String?,
    displayMac: String?,
    manualModelOverride: BudsProtocol.BudsModel?,
    unsupportedDeviceName: String?,
    findIsPlaying: Boolean,
    canRename: Boolean,
    onRetryConnect: () -> Unit,
    onOpenFindBuds: () -> Unit,
    onOpenRename: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = displayName
                            ?: unsupportedDeviceName
                            ?: stringResource(R.string.default_device_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            displayMac != null -> displayMac
                            deviceStatus == SystemDeviceStatus.UNSUPPORTED_DEVICE -> stringResource(R.string.unsupported_warning_title)
                            else -> stringResource(R.string.status_searching_paired)
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (manualModelOverride != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.drawer_manual_badge, manualModelOverride.displayName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Tonal status pill
                val (badgeText, badgeContainer, badgeContent) = when {
                    connectionState == BleConnectionState.CONNECTED -> Triple(
                        stringResource(R.string.status_connected),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    connectionState == BleConnectionState.CONNECTING -> Triple(
                        stringResource(R.string.status_connecting),
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    deviceStatus == SystemDeviceStatus.DEVICE_CONNECTED_OS -> Triple(
                        stringResource(R.string.status_online_connecting),
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    deviceStatus == SystemDeviceStatus.UNSUPPORTED_DEVICE -> Triple(
                        stringResource(R.string.status_unsupported),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                    deviceStatus == SystemDeviceStatus.NO_PAIRED_DEVICE -> Triple(
                        stringResource(R.string.status_not_paired),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    deviceStatus == SystemDeviceStatus.NO_PERMISSIONS -> Triple(
                        stringResource(R.string.status_no_permissions),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                    else -> Triple(
                        stringResource(R.string.status_in_case_disconnected),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = badgeContainer,
                    shape = CircleShape
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeContent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Status hint (hidden when connected)
            val hintText = when {
                connectionState == BleConnectionState.CONNECTED -> null
                connectionState == BleConnectionState.CONNECTING -> stringResource(R.string.hint_connecting)
                deviceStatus == SystemDeviceStatus.UNSUPPORTED_DEVICE -> stringResource(R.string.hint_unsupported)
                deviceStatus == SystemDeviceStatus.DEVICE_DISCONNECTED -> stringResource(R.string.hint_disconnected)
                deviceStatus == SystemDeviceStatus.NO_PAIRED_DEVICE -> stringResource(R.string.hint_not_paired)
                deviceStatus == SystemDeviceStatus.NO_PERMISSIONS -> stringResource(R.string.hint_no_permissions)
                else -> stringResource(R.string.hint_waiting_activity)
            }
            if (hintText != null) {
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expressive Quick Actions Row: Find My Earbuds (universal) + Rename
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenFindBuds,
                    shape = CircleShape,
                    colors = if (findIsPlaying) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (findIsPlaying) Icons.Rounded.StopCircle else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(if (findIsPlaying) R.string.find_buds_playing_badge else R.string.find_buds_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (canRename) {
                    FilledTonalButton(
                        onClick = onOpenRename,
                        shape = CircleShape,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.weight(0.85f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.rename_action),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Manual reconnect button when disconnected but OS-connected
            if (device != null && connectionState == BleConnectionState.DISCONNECTED && deviceStatus == SystemDeviceStatus.DEVICE_CONNECTED_OS) {
                Button(
                    onClick = onRetryConnect,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_reconnect))
                }
            }
        }
    }
}

@Composable
fun SingleHeadsetBatteryCard(
    battery: BudsProtocol.BatteryInfo,
    titleRes: Int = R.string.battery_headset,
    voltageMv: Int?,
    hoursLeft: Float?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Headset,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val meta = buildList {
                            if (hoursLeft != null && hoursLeft > 0f) {
                                add(stringResource(R.string.battery_hours_left, hoursLeft))
                            }
                            if (voltageMv != null && voltageMv > 0) {
                                add(stringResource(R.string.battery_voltage_mv, voltageMv))
                            }
                        }.joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (battery.level >= 0) "${battery.level}%" else stringResource(R.string.dash),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (battery.isCharging) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { if (battery.level >= 0) battery.level / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = when {
                    battery.isCharging -> MaterialTheme.colorScheme.primary
                    battery.level in 0..19 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun StandardBluetoothModeCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.bt_standard_fallback_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun BatteryCard(
    modifier: Modifier = Modifier,
    title: String,
    battery: BudsProtocol.BatteryInfo,
    icon: ImageVector,
    subtitle: String? = null,
    isCached: Boolean = false
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCached) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isCached) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (battery.level >= 0) "${battery.level}%" else stringResource(R.string.dash),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = if (isCached) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (battery.isCharging && !isCached) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LinearProgressIndicator(
                progress = { if (battery.level >= 0) battery.level / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = when {
                    isCached -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    battery.isCharging -> MaterialTheme.colorScheme.primary
                    battery.level in 0..19 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun LogitechG733ControlsCard(
    sidetoneLevel: Int?,
    lightsEnabled: Boolean?,
    inactiveMinutes: Int?,
    enabled: Boolean,
    onSidetoneChanged: (Int) -> Unit,
    onLightsToggle: (Boolean) -> Unit,
    onInactiveSelected: (Int) -> Unit
) {
    var sliderVal by remember(sidetoneLevel) { mutableStateOf((sidetoneLevel ?: 0).toFloat()) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sidetone Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.logitech_sidetone_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.logitech_sidetone_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${sliderVal.roundToInt()}%",
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = sliderVal,
                    onValueChange = { sliderVal = it },
                    onValueChangeFinished = { onSidetoneChanged(sliderVal.roundToInt()) },
                    valueRange = 0f..100f,
                    enabled = enabled
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            // RGB Lighting Switch
            val lightsOn = lightsEnabled ?: false
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.logitech_lights_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.logitech_lights_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = lightsOn,
                    enabled = enabled,
                    onCheckedChange = { onLightsToggle(it) },
                    thumbContent = if (lightsOn) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            // Auto power-off timer
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.logitech_inactive_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.logitech_inactive_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val presets = listOf(0, 5, 15, 30, 60, 90)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { mins ->
                        val selected = inactiveMinutes == mins
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable(enabled = enabled) { onInactiveSelected(mins) }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AutoSizeSingleLineText(
                                text = if (mins == 0) stringResource(R.string.logitech_inactive_never)
                                else stringResource(R.string.logitech_inactive_min, mins),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxFontSize = 11.sp,
                                minFontSize = 8.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BassBoostLevelCard(
    enabled: Boolean,
    checked: Boolean?,
    level: Int,
    onToggle: (Boolean) -> Unit,
    onLevelSelected: (Int) -> Unit
) {
    val isChecked = checked ?: false
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isChecked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = if (isChecked) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.ultra_bass_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isChecked) stringResource(R.string.ultra_bass_status_on, level)
                        else stringResource(R.string.ultra_bass_status_off),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isChecked,
                    enabled = enabled,
                    onCheckedChange = { onToggle(it) },
                    thumbContent = if (isChecked) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else null
                )
            }

            if (isChecked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (lvl in 1..5) {
                        val selected = lvl == level
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable(enabled = enabled) { onLevelSelected(lvl) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$lvl",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AncModeSelector(
    currentMode: AncMode?,
    currentLevel: HuaweiProtocol.AncLevel?,
    supportsAncLevel: Boolean,
    supportsAncDynamic: Boolean,
    galaxyAdaptiveOnly: Boolean = false,
    useLowMidHighLabels: Boolean,
    enabled: Boolean,
    onModeSelected: (AncMode) -> Unit,
    onLevelSelected: (HuaweiProtocol.AncLevel) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val modes = AncMode.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    val selected = mode == currentMode
                    SegmentedButton(
                        selected = selected,
                        enabled = enabled,
                        onClick = { onModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        icon = {}
                    ) {
                        AutoSizeSingleLineText(
                            text = stringResource(mode.titleRes),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = LocalContentColor.current,
                            maxFontSize = 13.sp,
                            minFontSize = 9.sp
                        )
                    }
                }
            }

            if (supportsAncLevel && currentMode == AncMode.NOISE_REDUCTION) {
                val levels = if (galaxyAdaptiveOnly) {
                    listOf(HuaweiProtocol.AncLevel.ULTRA, HuaweiProtocol.AncLevel.DYNAMIC)
                } else {
                    buildList {
                        add(HuaweiProtocol.AncLevel.COMFORT)
                        add(HuaweiProtocol.AncLevel.GENERAL)
                        add(HuaweiProtocol.AncLevel.ULTRA)
                        if (supportsAncDynamic) add(HuaweiProtocol.AncLevel.DYNAMIC)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    levels.forEach { lvl ->
                        val selected = lvl == currentLevel
                        val lvlTitleRes = if (useLowMidHighLabels) {
                            when (lvl) {
                                HuaweiProtocol.AncLevel.COMFORT -> R.string.anc_level_nothing_low
                                HuaweiProtocol.AncLevel.GENERAL -> R.string.anc_level_nothing_mid
                                HuaweiProtocol.AncLevel.ULTRA -> R.string.anc_level_nothing_high
                                HuaweiProtocol.AncLevel.DYNAMIC -> R.string.anc_level_nothing_adaptive
                            }
                        } else {
                            lvl.titleRes
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable(enabled = enabled) { onLevelSelected(lvl) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AutoSizeSingleLineText(
                                text = stringResource(lvlTitleRes),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else if (enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                maxFontSize = 12.sp,
                                minFontSize = 8.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EqPresetSelector(
    currentPreset: BudsProtocol.EqPreset?,
    activeCustomName: String?,
    activeCustomId: Int?,
    customProfiles: List<BudsProtocol.CustomEqProfile>,
    customEqSpec: BudsProtocol.CustomEqSpec?,
    showPresets: Boolean,
    model: BudsProtocol.BudsModel,
    enabled: Boolean,
    onPresetSelected: (BudsProtocol.EqPreset) -> Unit,
    onSelectCustomProfile: (BudsProtocol.CustomEqProfile) -> Unit,
    onEditCustomProfile: (BudsProtocol.CustomEqProfile) -> Unit,
    onDeleteCustomProfile: (BudsProtocol.CustomEqProfile) -> Unit,
    onCreateCustomProfile: () -> Unit,
    onOpenDirectCustomEq: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showPresets) {
                val presets = if (model.isHonorChoice) {
                    listOf(
                        BudsProtocol.EqPreset.ORIGINAL_SOUND,
                        BudsProtocol.EqPreset.DEEP_BASS,
                        BudsProtocol.EqPreset.SERENADE
                    )
                } else {
                    listOf(
                        BudsProtocol.EqPreset.SERENADE,
                        BudsProtocol.EqPreset.ORIGINAL_SOUND,
                        BudsProtocol.EqPreset.PURE_BASS,
                        BudsProtocol.EqPreset.DEEP_BASS
                    )
                }

                for (row in presets.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (preset in row) {
                            val selected = (preset == currentPreset) && (activeCustomName == null)
                            val titleRes = when {
                                model.isHuawei -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_huawei_voices
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_huawei_default
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_huawei_treble
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_huawei_bass
                                }
                                model.isHonorChoice -> when (preset) {
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_nothing_balanced
                                    BudsProtocol.EqPreset.DEEP_BASS,
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_huawei_bass
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_huawei_voices
                                }
                                model.isNothing && model.nothingUsesListeningModeEq -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_cmf_vocals
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_cmf_pop
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_cmf_electronic
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_cmf_rock
                                }
                                model.isNothing -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_nothing_voice
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_nothing_balanced
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_nothing_more_treble
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_nothing_more_bass
                                }
                                model.isGalaxy -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_galaxy_clear
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_galaxy_normal
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_galaxy_dynamic
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_galaxy_bass
                                }
                                model.isSony -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_huawei_voices
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.anc_off
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_sony_bright
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_huawei_bass
                                }
                                model.isXiaomi -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_huawei_voices
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_huawei_default
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_huawei_treble
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_huawei_bass
                                }
                                model.isJbl -> when (preset) {
                                    BudsProtocol.EqPreset.SERENADE -> R.string.eq_huawei_voices
                                    BudsProtocol.EqPreset.ORIGINAL_SOUND -> R.string.eq_jbl_studio
                                    BudsProtocol.EqPreset.PURE_BASS -> R.string.eq_jbl_club
                                    BudsProtocol.EqPreset.DEEP_BASS -> R.string.eq_huawei_bass
                                }
                                else -> preset.titleRes
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = enabled) { onPresetSelected(preset) },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 13.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = when {
                                            selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                            enabled -> MaterialTheme.colorScheme.onSurface
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Custom Equalizer profiles & curve editor
            if (customEqSpec != null) {
                if (showPresets) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // Saved profiles list (realme / OnePlus / OPPO / Huawei)
                if (customEqSpec.supportsMultipleProfiles && customProfiles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        customProfiles.forEach { profile ->
                            val isSelected = (currentPreset == null) && (
                                (activeCustomId != null && activeCustomId == profile.id) ||
                                (activeCustomId == null && profile.isSelected)
                            )
                            Surface(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = enabled) { onSelectCustomProfile(profile) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.GraphicEq,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { onEditCustomProfile(profile) },
                                            enabled = enabled,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Tune,
                                                contentDescription = stringResource(R.string.eq_custom_title),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteCustomProfile(profile) },
                                            enabled = enabled,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = stringResource(R.string.delete),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Primary Custom EQ entry pill
                Surface(
                    color = if (currentPreset == null && activeCustomName != null && !customEqSpec.supportsMultipleProfiles) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = enabled) {
                            if (customEqSpec.supportsMultipleProfiles) onCreateCustomProfile()
                            else onOpenDirectCustomEq()
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (customEqSpec.supportsMultipleProfiles) Icons.Rounded.Add else Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (customEqSpec.supportsMultipleProfiles) {
                                        stringResource(R.string.eq_custom_new_profile)
                                    } else {
                                        stringResource(R.string.eq_custom_title)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = stringResource(
                                        R.string.eq_custom_subtitle,
                                        customEqSpec.bandLabels.size,
                                        customEqSpec.minDb,
                                        customEqSpec.maxDb
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else if (activeCustomName != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.eq_custom_active, activeCustomName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButtonSettingsCard(
    selectedSide: TouchSide,
    gestures: List<TouchGesture>,
    touchSettings: Map<Pair<TouchSide, TouchGesture>, TouchAction>,
    enabled: Boolean,
    onSideSelected: (TouchSide) -> Unit,
    onGestureClick: (TouchGesture) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sides = TouchSide.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sides.forEachIndexed { index, side ->
                    SegmentedButton(
                        selected = side == selectedSide,
                        enabled = enabled,
                        onClick = { onSideSelected(side) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sides.size),
                        icon = {}
                    ) {
                        Text(
                            text = stringResource(
                                if (side == TouchSide.LEFT) R.string.touch_side_left_earbud
                                else R.string.touch_side_right_earbud
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            gestures.forEachIndexed { index, gesture ->
                val action = touchSettings[Pair(selectedSide, gesture)]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled) { onGestureClick(gesture) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(gesture.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (action != null) stringResource(action.titleRes) else stringResource(R.string.dash),
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                action != null -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (index < gestures.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun GestureActionSelectionDialog(
    model: BudsProtocol.BudsModel,
    side: TouchSide,
    gesture: TouchGesture,
    currentAction: TouchAction?,
    onActionSelected: (TouchAction) -> Unit,
    onDismiss: () -> Unit
) {
    val allowedActions = remember(model, gesture, currentAction) {
        val base = when {
            model.isHuawei -> HuaweiProtocol.getAvailableActions(model, gesture)
            model.isNothing -> NothingProtocol.getAvailableActions(gesture)
            else -> getAvailableActions(gesture)
        }
        val list = base.toMutableList()
        if (currentAction != null && currentAction !in list) {
            list.add(0, currentAction)
        }
        list
    }

    val sideName = stringResource(if (side == TouchSide.LEFT) R.string.touch_side_left_earbud else R.string.touch_side_right_earbud)
    val gestureName = stringResource(gesture.titleRes)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.dialog_gesture_title, sideName, gestureName),
                style = MaterialTheme.typography.titleLarge
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
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onActionSelected(action) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onActionSelected(action) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(action.titleRes),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialAudioCard(
    spatialAudio: Boolean?,
    spatialAudioMode: Int = 0,
    nothingSpatialModes: List<Int> = emptyList(),
    isPlayingDemo: Boolean,
    showRealmeDemo: Boolean,
    allowToggleWhenUnknown: Boolean = false,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectNothingMode: (Int) -> Unit = {},
    onToggleDemo: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.section_spatial_audio),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.spatial_audio_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (nothingSpatialModes.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    nothingSpatialModes.forEachIndexed { index, mode ->
                        val labelRes = when (mode) {
                            NothingProtocol.SPATIAL_FIXED -> R.string.spatial_mode_fixed
                            NothingProtocol.SPATIAL_HEAD_TRACKING -> R.string.spatial_mode_head_tracking
                            NothingProtocol.SPATIAL_CONCERT -> R.string.spatial_mode_concert
                            NothingProtocol.SPATIAL_CINEMA -> R.string.spatial_mode_cinema
                            else -> R.string.spatial_audio_off
                        }
                        SegmentedButton(
                            selected = spatialAudioMode == mode,
                            enabled = enabled,
                            onClick = { onSelectNothingMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = nothingSpatialModes.size),
                            icon = {}
                        ) {
                            AutoSizeSingleLineText(
                                text = stringResource(labelRes),
                                fontWeight = if (spatialAudioMode == mode) FontWeight.SemiBold else FontWeight.Medium,
                                color = LocalContentColor.current,
                                maxFontSize = 13.sp,
                                minFontSize = 9.sp
                            )
                        }
                    }
                }
            } else {
                val options = listOf(
                    false to stringResource(R.string.spatial_audio_off),
                    true to stringResource(R.string.spatial_audio_on)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = spatialAudio == value,
                            enabled = enabled && (spatialAudio != null || allowToggleWhenUnknown),
                            onClick = { onToggle(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            if (showRealmeDemo) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                Surface(
                    color = if (isPlayingDemo) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onToggleDemo() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isPlayingDemo) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = if (isPlayingDemo) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isPlayingDemo) stringResource(R.string.demo_audio_playing) else stringResource(R.string.demo_audio_play),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isPlayingDemo) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = if (spatialAudio == true) stringResource(R.string.demo_sample_spatial) else stringResource(R.string.demo_sample_stereo),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isPlayingDemo) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        if (isPlayingDemo) {
                            Text(
                                text = stringResource(R.string.demo_audio_stop),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SwitchFeatureCard(
    title: String,
    description: String,
    icon: ImageVector? = null,
    checked: Boolean?,
    enabled: Boolean,
    allowToggleWhenUnknown: Boolean = false,
    onToggle: (Boolean) -> Unit
) {
    val isChecked = checked ?: false
    val isInteractive = enabled && (checked != null || allowToggleWhenUnknown)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isChecked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isChecked) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isChecked,
                enabled = isInteractive,
                onCheckedChange = { onToggle(it) },
                thumbContent = if (isChecked) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
fun DualConnectionCard(
    dualConnection: Boolean?,
    connectedDevices: List<BudsProtocol.ConnectedDeviceInfo>,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val isChecked = dualConnection ?: false
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isChecked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Devices,
                        contentDescription = null,
                        tint = if (isChecked) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.dual_connection_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.dual_connection_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isChecked,
                    enabled = enabled && dualConnection != null,
                    onCheckedChange = { onToggle(it) },
                    thumbContent = if (isChecked) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else null
                )
            }

            if (connectedDevices.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Text(
                    text = stringResource(R.string.dual_connection_history),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    imageVector = Icons.Rounded.Smartphone,
                                    contentDescription = null,
                                    tint = if (dev.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = dev.name.ifBlank { dev.mac },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = dev.mac,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = stringResource(
                                    if (dev.isConnected) R.string.dual_connection_device_connected
                                    else R.string.dual_connection_device_disconnected
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (dev.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutoSizeSingleLineText(
    text: String,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    maxFontSize: TextUnit = 13.sp,
    minFontSize: TextUnit = 9.sp,
    modifier: Modifier = Modifier
) {
    var fontSize by remember(text, maxFontSize) { mutableStateOf(maxFontSize) }
    Text(
        text = text,
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > minFontSize.value) {
                fontSize = (fontSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
            }
        }
    )
}

@Composable
fun UseTimeCard(
    leftMinutes: Int?,
    rightMinutes: Int?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.use_time_total),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leftMinutes != null) {
                    UseTimeSideItem(
                        badge = "L",
                        minutes = leftMinutes,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rightMinutes != null) {
                    UseTimeSideItem(
                        badge = "R",
                        minutes = rightMinutes,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun UseTimeSideItem(
    badge: String,
    minutes: Int,
    modifier: Modifier = Modifier
) {
    val hours = minutes / 60
    val mins = minutes % 60
    val formatted = if (hours > 0) {
        stringResource(R.string.use_time_format_hm, hours, mins)
    } else {
        stringResource(R.string.use_time_format_m, mins)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Text(
            text = formatted,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
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

    LaunchedEffect(logs.size, isExpanded) {
        if (isExpanded && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.logs_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${logs.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isExpanded) {
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
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.logs_copy_desc),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.logs_clear_desc),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (isExpanded) stringResource(R.string.logs_collapse_desc) else stringResource(R.string.logs_expand_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))

                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.logs_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(10.dp)
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
                                            log.contains("RX") -> MaterialTheme.colorScheme.tertiary
                                            log.contains("TX") -> MaterialTheme.colorScheme.primary
                                            log.contains("Error") || log.contains("unavailable") -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
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
