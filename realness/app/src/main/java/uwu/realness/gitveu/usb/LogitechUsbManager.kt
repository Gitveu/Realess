package uwu.realness.gitveu.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uwu.realness.gitveu.protocol.LogitechProtocol
import uwu.realness.gitveu.protocol.T300Protocol

/**
 * Android USB-OTG HID manager for Logitech G733 (HID++ 2.0) and G435 (046D:0ACB Vendor FFC0 Report 6).
 */
class LogitechUsbManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "uwu.realness.gitveu.USB_PERMISSION"
        private const val HID_REQ_GET_REPORT = 0x01
        private const val HID_REQ_SET_REPORT = 0x09
        private const val HID_REPORT_TYPE_OUTPUT = 0x02
        private const val HID_REPORT_TYPE_FEATURE = 0x03
        private const val REQ_HOST_TO_IFACE = 0x21
        private const val REQ_IFACE_TO_HOST = 0xA1
        private const val G733_POLL_MS = 12_000L
        private const val G435_POLL_MS = 90_000L // gentle poll so bridge mode rarely interrupts audio
    }

    data class UsbHeadsetInfo(
        val device: UsbDevice,
        val model: T300Protocol.BudsModel,
        val displayName: String,
        val usbId: String,
        val hasPermission: Boolean
    )

    data class UsbTelemetry(
        val online: Boolean,
        val percent: Int,
        val charging: Boolean,
        val voltageMv: Int?,
        val hoursLeft: Float?,
        val sidetoneLevel: Int? = null,
        val lightsEnabled: Boolean? = null,
        val inactiveMinutes: Int? = null
    )

    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioMutex = Mutex()
    private val g435Estimator = LogitechProtocol.G435BatteryEstimator()

    private val _detectedUsbHeadset = MutableStateFlow<UsbHeadsetInfo?>(null)
    val detectedUsbHeadset: StateFlow<UsbHeadsetInfo?> = _detectedUsbHeadset.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _telemetry = MutableStateFlow<UsbTelemetry?>(null)
    val telemetry: StateFlow<UsbTelemetry?> = _telemetry.asStateFlow()

    private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private var activeDevice: UsbDevice? = null
    private var activeModel: T300Protocol.BudsModel = T300Protocol.BudsModel.UNKNOWN
    private var pollJob: Job? = null
    private var g435Seq = 1

    private fun log(msg: String) {
        _logFlow.tryEmit(msg)
    }

    fun scanDevices(): UsbHeadsetInfo? {
        val mgr = usbManager ?: return null
        val match = mgr.deviceList.values.firstOrNull {
            LogitechProtocol.modelFromUsbIds(it.vendorId, it.productId) != null
        }
        if (match == null) {
            if (_detectedUsbHeadset.value != null) {
                disconnect()
            }
            _detectedUsbHeadset.value = null
            return null
        }
        val model = LogitechProtocol.modelFromUsbIds(match.vendorId, match.productId)!!
        val hasPerm = mgr.hasPermission(match)
        val usbId = "USB %04X:%04X".format(match.vendorId, match.productId)
        val prodName = try {
            match.productName?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } ?: model.displayName
        val info = UsbHeadsetInfo(
            device = match,
            model = model,
            displayName = prodName,
            usbId = usbId,
            hasPermission = hasPerm
        )
        _detectedUsbHeadset.value = info
        if (hasPerm && (!_connected.value || activeDevice?.deviceName != match.deviceName)) {
            startSession(match, model)
        }
        return info
    }

    fun requestPermission(device: UsbDevice? = _detectedUsbHeadset.value?.device) {
        val mgr = usbManager ?: return
        val target = device ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 435733, intent, flags)
        log("USB: requesting permission for %04X:%04X".format(target.vendorId, target.productId))
        mgr.requestPermission(target, pi)
    }

    fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        if (!granted || device == null) {
            log("USB: permission denied")
            scanDevices()
            return
        }
        val model = LogitechProtocol.modelFromUsbIds(device.vendorId, device.productId) ?: return
        log("USB: permission granted for ${model.displayName}")
        scanDevices()
        startSession(device, model)
    }

    private fun startSession(device: UsbDevice, model: T300Protocol.BudsModel) {
        pollJob?.cancel()
        activeDevice = device
        activeModel = model
        _connected.value = true
        g435Estimator.reset()
        log("✓ USB HID session started: ${model.displayName} (%04X:%04X)".format(device.vendorId, device.productId))

        pollJob = scope.launch {
            refreshNowInternal()
            val interval = if (model == T300Protocol.BudsModel.LOGITECH_G435) G435_POLL_MS else G733_POLL_MS
            while (isActive && _connected.value) {
                delay(interval)
                if (!_connected.value) break
                refreshNowInternal()
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        activeDevice = null
        activeModel = T300Protocol.BudsModel.UNKNOWN
        _connected.value = false
        _telemetry.value = null
    }

    fun refreshNow() {
        if (!_connected.value) {
            scanDevices()
            return
        }
        scope.launch { refreshNowInternal() }
    }

    private suspend fun refreshNowInternal() {
        val dev = activeDevice ?: return
        val model = activeModel
        ioMutex.withLock {
            when (model) {
                T300Protocol.BudsModel.LOGITECH_G733 -> pollG733(dev)
                T300Protocol.BudsModel.LOGITECH_G435 -> pollG435(dev)
                else -> {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // G733 HID++ 2.0 Controls & Battery
    // -------------------------------------------------------------------------

    fun setG733Sidetone(level: Int) {
        val dev = activeDevice ?: return
        if (activeModel != T300Protocol.BudsModel.LOGITECH_G733) return
        val clamped = level.coerceIn(0, 100)
        scope.launch {
            ioMutex.withLock {
                val pkt = LogitechProtocol.buildG733SidetoneSet(clamped)
                val ok = sendG733Packet(dev, pkt)
                log("TX G733 Sidetone [$clamped%] -> ${if (ok) "OK" else "ERR"}")
                if (ok) {
                    val cur = _telemetry.value
                    _telemetry.value = (cur ?: UsbTelemetry(true, -1, false, null, null)).copy(sidetoneLevel = clamped)
                }
            }
        }
    }

    fun setG733Lights(on: Boolean) {
        val dev = activeDevice ?: return
        if (activeModel != T300Protocol.BudsModel.LOGITECH_G733) return
        scope.launch {
            ioMutex.withLock {
                val pkts = LogitechProtocol.buildG733LightsPackets(on)
                var ok = true
                for ((idx, pkt) in pkts.withIndex()) {
                    if (!sendG733Packet(dev, pkt)) ok = false
                    if (idx == 0) delay(5)
                }
                log("TX G733 RGB Lights [${if (on) "ON" else "OFF"}] -> ${if (ok) "OK" else "ERR"}")
                if (ok) {
                    val cur = _telemetry.value
                    _telemetry.value = (cur ?: UsbTelemetry(true, -1, false, null, null)).copy(lightsEnabled = on)
                }
            }
        }
    }

    fun setG733InactiveTime(minutes: Int) {
        val dev = activeDevice ?: return
        if (activeModel != T300Protocol.BudsModel.LOGITECH_G733) return
        val clamped = minutes.coerceIn(0, 90)
        scope.launch {
            ioMutex.withLock {
                val pkt = LogitechProtocol.buildG733InactiveTimeSet(clamped)
                val ok = sendG733Packet(dev, pkt)
                log("TX G733 InactiveTime [${clamped}m] -> ${if (ok) "OK" else "ERR"}")
                if (ok) {
                    val cur = _telemetry.value
                    _telemetry.value = (cur ?: UsbTelemetry(true, -1, false, null, null)).copy(inactiveMinutes = clamped)
                }
            }
        }
    }

    private fun findHidInterfaces(device: UsbDevice): List<UsbInterface> {
        val hid = mutableListOf<UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                hid.add(iface)
            }
        }
        // Prefer vendor HID interface with highest interface id (audio interfaces come first on Logitech dongles)
        return hid.sortedByDescending { it.id }
    }

    private fun findEndpoints(iface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
            }
        }
        return epIn to epOut
    }

    private fun writeHidOutput(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        epOut: UsbEndpoint?,
        data: ByteArray
    ): Boolean {
        if (epOut != null) {
            val sent = conn.bulkTransfer(epOut, data, data.size, 800)
            if (sent >= 0) return true
        }
        val reportId = data[0].toInt() and 0xFF
        val wValue = (HID_REPORT_TYPE_OUTPUT shl 8) or reportId
        val res = conn.controlTransfer(REQ_HOST_TO_IFACE, HID_REQ_SET_REPORT, wValue, iface.id, data, data.size, 800)
        return res >= 0
    }

    private fun sendG733Packet(device: UsbDevice, packet: ByteArray): Boolean {
        val mgr = usbManager ?: return false
        if (!mgr.hasPermission(device)) return false
        val conn = mgr.openDevice(device) ?: return false
        try {
            for (iface in findHidInterfaces(device)) {
                if (!conn.claimInterface(iface, true)) continue
                try {
                    val (_, epOut) = findEndpoints(iface)
                    if (writeHidOutput(conn, iface, epOut, packet)) {
                        return true
                    }
                } finally {
                    conn.releaseInterface(iface)
                }
            }
            return false
        } finally {
            conn.close()
        }
    }

    private fun pollG733(device: UsbDevice) {
        val mgr = usbManager ?: return
        if (!mgr.hasPermission(device)) return
        val conn = mgr.openDevice(device) ?: return
        try {
            val req = LogitechProtocol.buildG733BatteryRequest()
            for (iface in findHidInterfaces(device)) {
                if (!conn.claimInterface(iface, true)) continue
                try {
                    val (epIn, epOut) = findEndpoints(iface)
                    if (epIn == null) continue
                    if (!writeHidOutput(conn, iface, epOut, req)) continue

                    val readBuf = ByteArray(maxOf(20, epIn.maxPacketSize))
                    val deadline = System.currentTimeMillis() + 1200L
                    while (System.currentTimeMillis() < deadline) {
                        val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 250)
                        if (n >= 7) {
                            val slice = readBuf.copyOfRange(0, n)
                            val parsed = LogitechProtocol.parseG733BatteryResponse(slice)
                            if (parsed != null) {
                                val prev = _telemetry.value
                                _telemetry.value = UsbTelemetry(
                                    online = parsed.online,
                                    percent = parsed.percent,
                                    charging = parsed.charging,
                                    voltageMv = parsed.voltageMv.takeIf { it > 0 },
                                    hoursLeft = parsed.hoursLeft,
                                    sidetoneLevel = prev?.sidetoneLevel,
                                    lightsEnabled = prev?.lightsEnabled,
                                    inactiveMinutes = prev?.inactiveMinutes
                                )
                                if (parsed.online) {
                                    log("RX G733 Battery: ${parsed.percent}% (${parsed.voltageMv} mV${if (parsed.charging) ", charging" else ""})")
                                } else {
                                    log("RX G733 Battery: headset offline")
                                }
                                return
                            }
                        }
                    }
                } finally {
                    conn.releaseInterface(iface)
                }
            }
            log("G733: no HID++ battery response")
        } catch (e: Exception) {
            log("G733 USB error: ${e.message}")
        } finally {
            conn.close()
        }
    }

    // -------------------------------------------------------------------------
    // G435 Vendor FFC0 Report 6 Bridge Battery Poll (with 1 retry)
    // -------------------------------------------------------------------------

    private suspend fun pollG435(device: UsbDevice) {
        val first = readG435BatteryOnce(device)
        val result = if (first == G435ReadOutcome.NoReply) {
            delay(1200)
            readG435BatteryOnce(device)
        } else {
            first
        }
        when (result) {
            is G435ReadOutcome.Voltage -> {
                val est = g435Estimator.addSample(result.mv)
                _telemetry.value = UsbTelemetry(
                    online = true,
                    percent = est.percent,
                    charging = est.charging,
                    voltageMv = est.voltageMv,
                    hoursLeft = est.hoursLeft
                )
                val hrsStr = est.hoursLeft?.let { " (~%.1fh)".format(it) } ?: ""
                log("RX G435 Battery: ${est.percent}% (${est.voltageMv} mV${if (est.charging) ", charging" else ""}$hrsStr)")
            }
            G435ReadOutcome.HeadsetOff -> {
                _telemetry.value = UsbTelemetry(
                    online = false,
                    percent = -1,
                    charging = false,
                    voltageMv = null,
                    hoursLeft = null
                )
                log("RX G435 Battery: headset off / sleeping")
            }
            G435ReadOutcome.NoReply -> {
                log("G435 USB: no reply from dongle bridge")
            }
        }
    }

    private sealed class G435ReadOutcome {
        data class Voltage(val mv: Int) : G435ReadOutcome()
        data object HeadsetOff : G435ReadOutcome()
        data object NoReply : G435ReadOutcome()
    }

    private fun g435SetFeature(conn: UsbDeviceConnection, ifaceId: Int, report: ByteArray): Boolean {
        val wValue = (HID_REPORT_TYPE_FEATURE shl 8) or (LogitechProtocol.G435_REPORT_ID.toInt() and 0xFF)
        return conn.controlTransfer(REQ_HOST_TO_IFACE, HID_REQ_SET_REPORT, wValue, ifaceId, report, report.size, 800) >= 0
    }

    private fun g435GetFeature(conn: UsbDeviceConnection, ifaceId: Int): ByteArray? {
        val buf = ByteArray(LogitechProtocol.G435_FEATURE_LEN)
        buf[0] = LogitechProtocol.G435_REPORT_ID
        val wValue = (HID_REPORT_TYPE_FEATURE shl 8) or (LogitechProtocol.G435_REPORT_ID.toInt() and 0xFF)
        val n = conn.controlTransfer(REQ_IFACE_TO_HOST, HID_REQ_GET_REPORT, wValue, ifaceId, buf, buf.size, 800)
        return if (n >= 2) buf else null
    }

    private fun g435Bridge(
        conn: UsbDeviceConnection,
        ifaceId: Int,
        epIn: UsbEndpoint?,
        op: ByteArray,
        body: ByteArray = ByteArray(0)
    ): Boolean {
        val report = LogitechProtocol.buildG435BridgeReport(op, body)
        if (!g435SetFeature(conn, ifaceId, report)) return false
        if (epIn == null) {
            Thread.sleep(60)
            return true
        }
        val inBuf = ByteArray(maxOf(LogitechProtocol.G435_INPUT_LEN, epIn.maxPacketSize))
        val deadline = System.currentTimeMillis() + 800L
        while (System.currentTimeMillis() < deadline) {
            val n = conn.bulkTransfer(epIn, inBuf, inBuf.size, 100)
            if (n >= 2 && inBuf[0] == LogitechProtocol.G435_REPORT_ID) {
                return true
            }
        }
        return false
    }

    private fun <T> g435Query(
        conn: UsbDeviceConnection,
        ifaceId: Int,
        epIn: UsbEndpoint?,
        category: Byte,
        len: Int,
        payload: ByteArray,
        parse: (ByteArray) -> T?
    ): T? {
        val body = LogitechProtocol.buildG435QueryBody(category, g435Seq, len, payload)
        g435Seq = (g435Seq % 127) + 1
        if (!g435Bridge(conn, ifaceId, epIn, LogitechProtocol.G435_OP_SEND_COMMAND, body)) return null
        val deadline = System.currentTimeMillis() + 1500L
        while (System.currentTimeMillis() < deadline) {
            val buf = g435GetFeature(conn, ifaceId) ?: break
            if ((buf[1].toInt() and 0xFF) != 0xFF) {
                val parsed = parse(buf)
                if (parsed != null) return parsed
                continue
            }
            Thread.sleep(30)
        }
        return null
    }

    private fun readG435BatteryOnce(device: UsbDevice): G435ReadOutcome {
        val mgr = usbManager ?: return G435ReadOutcome.NoReply
        if (!mgr.hasPermission(device)) return G435ReadOutcome.NoReply
        val conn = mgr.openDevice(device) ?: return G435ReadOutcome.NoReply
        try {
            for (iface in findHidInterfaces(device)) {
                if (!conn.claimInterface(iface, true)) continue
                var entered = false
                try {
                    val (epIn, _) = findEndpoints(iface)
                    if (!g435Bridge(conn, iface.id, epIn, LogitechProtocol.G435_OP_ENTER_BOOTLOADER)) {
                        continue
                    }
                    entered = true

                    // Drain stale feature reports
                    for (i in 0 until 16) {
                        val b = g435GetFeature(conn, iface.id) ?: break
                        if ((b[1].toInt() and 0xFF) == 0xFF) break
                    }

                    val connected = g435Query(
                        conn, iface.id, epIn,
                        LogitechProtocol.G435_CAT_WIRELESS, 1,
                        byteArrayOf(LogitechProtocol.G435_GET_CONNECT)
                    ) { LogitechProtocol.parseG435Remote(it, LogitechProtocol.G435_GET_CONNECT) }
                    if (connected == 0) {
                        return G435ReadOutcome.HeadsetOff
                    }

                    val mode = g435Query(
                        conn, iface.id, epIn,
                        LogitechProtocol.G435_CAT_WIRELESS, 1,
                        byteArrayOf(LogitechProtocol.G435_GET_MODE)
                    ) { LogitechProtocol.parseG435Remote(it, LogitechProtocol.G435_GET_MODE) }
                    if (mode != 1) {
                        g435Query(
                            conn, iface.id, epIn,
                            LogitechProtocol.G435_CAT_WIRELESS, 2,
                            byteArrayOf(LogitechProtocol.G435_SET_MODE, 0x01)
                        ) { Unit }
                    }

                    val samples = ArrayList<Int>(3)
                    for (i in 0 until 3) {
                        val mv = g435Query(
                            conn, iface.id, epIn,
                            LogitechProtocol.G435_CAT_OTA, 1,
                            byteArrayOf(0x00, 0x31)
                        ) { LogitechProtocol.parseG435BatteryMv(it) }
                        if (mv != null) samples.add(mv)
                    }
                    if (samples.isNotEmpty()) {
                        samples.sort()
                        return G435ReadOutcome.Voltage(samples[samples.size / 2])
                    }
                } finally {
                    if (entered) {
                        try {
                            g435SetFeature(conn, iface.id, LogitechProtocol.buildG435BridgeReport(LogitechProtocol.G435_OP_EXIT_BOOTLOADER))
                        } catch (_: Exception) {}
                    }
                    conn.releaseInterface(iface)
                }
            }
            return G435ReadOutcome.NoReply
        } catch (e: Exception) {
            log("G435 USB error: ${e.message}")
            return G435ReadOutcome.NoReply
        } finally {
            conn.close()
        }
    }
}
