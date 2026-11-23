package uwu.realness.gitveu.tiny;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
final class BudsLink {
    static final int CONN_DISCONNECTED = 0;
    static final int CONN_CONNECTING = 1;
    static final int CONN_CONNECTED = 2;

    private static final UUID[] SPP_UUIDS = {
        UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"),
        UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae4"),
        UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3")
    };
    private static final UUID[] OPPO_UUIDS = {
        UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5"),
        UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] HW_UUIDS_P1 = {
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")
    };
    private static final UUID[] HW_UUIDS_P16 = {
        UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] NOTHING_UUIDS = {
        UUID.fromString("AEAC4A03-DFF5-498F-843A-34487CF133EB"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] GALAXY_MOD_UUIDS = {
        UUID.fromString("2E73A4AD-332D-41FC-90E2-16BEF06523F2"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] GALAXY_LEG_UUIDS = {
        UUID.fromString("00001102-0000-1000-8000-00805F9B34FD"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] SONY_UUIDS = {
        UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2"),
        UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] STD_SPP_UUIDS = {
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] XIAOMI_UUIDS = {
        UUID.fromString("0000FD2D-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] SENNHEISER_UUIDS = {
        UUID.fromString("A2129FF3-081B-4C45-8AFE-469D9C4842EC"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final UUID[] PIXEL_BUDS_UUIDS = {
        UUID.fromString("25E97FF7-24CE-4C4C-8951-F764A708F7B5"),
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    };
    private static final String JBL_SERVICE_UUID = "65786365-6c70-6f69-6e74-2e636f6d0000";
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    interface Listener {
        void onStateChanged(int connState, Protocol.State state);
        void onLog(String line);
        void onUnexpectedDisconnect();
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object writeLock = new Object();

    private volatile int connState = CONN_DISCONNECTED;
    private volatile int activeModel = Protocol.MODEL_UNKNOWN;
    private volatile String activeMac = null;
    private volatile int sessionGen = 0;

    private BluetoothSocket socket;
    private InputStream inStream;
    private OutputStream outStream;
    private Thread readerThread;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean useGatt;

    private final Protocol.State state = new Protocol.State();
    private final java.util.TreeMap<Integer, Protocol.MultiDevice> hwMultiMap = new java.util.TreeMap<>();
    private boolean galaxyMgrSent = false;
    private int nothingBassLvl = 3;
    private int sonyTxSeq = 0;
    private boolean xiaomiAuthed = false;
    private int pbChannel = 1;
    private byte[] jblGattAcc = new byte[0];
    private final Runnable pollTask;
    private final Runnable refreshTask;

    BudsLink(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.pollTask = new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED) return;
                pollCycle();
                main.postDelayed(this, 2000L);
            }
        };
        this.refreshTask = new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED) return;
                log("Auto-refreshing state");
                requestFullState(false);
            }
        };
    }

    int getConnState() { return connState; }

    boolean isConnectedOrConnectingTo(String mac, int model) {
        return connState != CONN_DISCONNECTED
            && mac != null && mac.equals(activeMac)
            && model == activeModel;
    }

    void connect(final BluetoothDevice device) {
        connect(device, Protocol.MODEL_UNKNOWN);
    }

    void connect(final BluetoothDevice device, final int overrideModel) {
        final int effModel = overrideModel != Protocol.MODEL_UNKNOWN ? overrideModel : Protocol.detectModel(device.getName());
        if (isConnectedOrConnectingTo(device.getAddress(), effModel)) {
            log("Already connected/connecting to " + device.getName() + ", skipping duplicate connect");
            return;
        }
        final int gen = ++sessionGen;
        closeResourcesOnly();
        activeModel = effModel;
        activeMac = device.getAddress();
        connState = CONN_CONNECTING;
        notifyState();
        log("Connecting to " + device.getName() + " (" + device.getAddress() + ", m=" + activeModel + ")...");

        io.execute(new Runnable() {
            @Override public void run() {
                if (gen != sessionGen) return;
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null) {
                    try { bm.getAdapter().cancelDiscovery(); } catch (Exception ignored) {}
                }
                if (Protocol.isJbl(activeModel)) {
                    log("JBL uses BLE GATT, connecting...");
                    connectGatt(device, gen);
                    return;
                }
                UUID[] uuids;
                if (Protocol.isHuawei(activeModel)) {
                    uuids = Protocol.prefersHwPrivateUuid(activeModel) ? HW_UUIDS_P16 : HW_UUIDS_P1;
                } else if (Protocol.isHonorChoice(activeModel)) {
                    uuids = HW_UUIDS_P1;
                } else if (Protocol.isNothing(activeModel)) {
                    uuids = NOTHING_UUIDS;
                } else if (Protocol.isGalaxy(activeModel)) {
                    uuids = activeModel == Protocol.MODEL_GALAXY_BUDS ? GALAXY_LEG_UUIDS
                        : (activeModel == Protocol.MODEL_GALAXY_PLUS ? HW_UUIDS_P1 : GALAXY_MOD_UUIDS);
                } else if (Protocol.isSony(activeModel)) {
                    uuids = SONY_UUIDS;
                } else if (Protocol.isBose(activeModel) || Protocol.isSoundcore(activeModel) || Protocol.isJabra(activeModel)) {
                    uuids = STD_SPP_UUIDS;
                } else if (Protocol.isXiaomi(activeModel)) {
                    uuids = XIAOMI_UUIDS;
                } else if (Protocol.isSennheiser(activeModel)) {
                    uuids = SENNHEISER_UUIDS;
                } else if (Protocol.isPixelBuds(activeModel)) {
                    uuids = PIXEL_BUDS_UUIDS;
                } else if (Protocol.isOppoOrOnePlus(activeModel)) {
                    uuids = OPPO_UUIDS;
                } else {
                    uuids = SPP_UUIDS;
                }
                for (UUID uuid : uuids) {
                    if (gen != sessionGen) return;
                    if (trySpp(device, uuid, false, gen) || trySpp(device, uuid, true, gen)) {
                        if (gen != sessionGen) { closeResourcesOnly(); return; }
                        useGatt = false;
                        connState = CONN_CONNECTED;
                        log("SPP connected (" + uuid.toString().substring(0, 8) + ")");
                        notifyState();
                        startReader(gen);
                        onReady(gen);
                        return;
                    }
                }
                if (gen != sessionGen) return;
                if (!Protocol.isRealme(activeModel)) {
                    log("SPP unavailable");
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen == sessionGen) disconnectInternal(true);
                        }
                    });
                    return;
                }
                log("SPP unavailable, falling back to GATT...");
                connectGatt(device, gen);
            }
        });
    }

    private boolean trySpp(BluetoothDevice dev, UUID uuid, boolean insecure, int gen) {
        BluetoothSocket s = null;
        try {
            if (gen != sessionGen) return false;
            s = insecure
                ? dev.createInsecureRfcommSocketToServiceRecord(uuid)
                : dev.createRfcommSocketToServiceRecord(uuid);
            s.connect();
            if (gen != sessionGen) {
                try { s.close(); } catch (Exception ignored) {}
                return false;
            }
            if (s.isConnected()) {
                socket = s;
                inStream = s.getInputStream();
                outStream = s.getOutputStream();
                return true;
            }
            s.close();
        } catch (Exception e) {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
        return false;
    }

    private void startReader(final int gen) {
        readerThread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[1024];
                byte[] acc = new byte[0];
                try {
                    while (gen == sessionGen && socket != null && socket.isConnected()) {
                        int n = inStream.read(buf);
                        if (n <= 0) break;
                        byte[] chunk = Arrays.copyOf(buf, n);
                        log("RX raw [" + Protocol.hex(chunk) + "]");
                        byte[] merged = new byte[acc.length + n];
                        System.arraycopy(acc, 0, merged, 0, acc.length);
                        System.arraycopy(chunk, 0, merged, acc.length, n);
                        if (Protocol.isHuawei(activeModel) || Protocol.isHonorChoice(activeModel)) {
                            List<HuaweiProto.Pkt> pkts = new ArrayList<>();
                            acc = HuaweiProto.parseStream(merged, pkts);
                            for (HuaweiProto.Pkt pkt : pkts) handleHwPacket(pkt);
                        } else if (Protocol.isNothing(activeModel)) {
                            List<NothingProto.Pkt> pkts = new ArrayList<>();
                            acc = NothingProto.parseStream(merged, pkts);
                            for (NothingProto.Pkt pkt : pkts) handleNothingPacket(pkt);
                        } else if (Protocol.isGalaxy(activeModel)) {
                            List<GalaxyProto.Pkt> pkts = new ArrayList<>();
                            acc = GalaxyProto.parseStream(merged, pkts);
                            for (GalaxyProto.Pkt pkt : pkts) handleGalaxyPacket(pkt);
                        } else if (Protocol.isSony(activeModel)) {
                            List<ExtraProtos.SonyPkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.sonyParseStream(merged, pkts);
                            for (ExtraProtos.SonyPkt pkt : pkts) handleSonyPacket(pkt);
                        } else if (Protocol.isBose(activeModel)) {
                            List<ExtraProtos.BosePkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.boseParseStream(merged, pkts);
                            for (ExtraProtos.BosePkt pkt : pkts) handleBosePacket(pkt);
                        } else if (Protocol.isSoundcore(activeModel)) {
                            List<ExtraProtos.ScPkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.scParseStream(merged, pkts);
                            for (ExtraProtos.ScPkt pkt : pkts) handleSoundcorePacket(pkt);
                        } else if (Protocol.isXiaomi(activeModel)) {
                            List<ExtraProtos.XiaomiPkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.xiaomiParseStream(merged, pkts);
                            for (ExtraProtos.XiaomiPkt pkt : pkts) handleXiaomiPacket(pkt);
                        } else if (Protocol.isSennheiser(activeModel)) {
                            List<ExtraProtos.GaiaPkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.gaiaParseStream(merged, pkts);
                            for (ExtraProtos.GaiaPkt pkt : pkts) handleSennheiserPacket(pkt);
                        } else if (Protocol.isJabra(activeModel)) {
                            List<ExtraProtos.JabraPkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.jabraParseStream(merged, pkts);
                            for (ExtraProtos.JabraPkt pkt : pkts) handleJabraPacket(pkt);
                        } else if (Protocol.isPixelBuds(activeModel)) {
                            List<ExtraProtos.PbPkt> pkts = new ArrayList<>();
                            acc = ExtraProtos.pbParseStream(merged, pkts);
                            for (ExtraProtos.PbPkt pkt : pkts) handlePixelBudsPacket(pkt);
                        } else {
                            List<Protocol.Frame> frames = new ArrayList<>();
                            acc = Protocol.parseStream(merged, frames);
                            for (Protocol.Frame f : frames) handleFrame(f);
                        }
                    }
                } catch (IOException e) {
                    if (gen == sessionGen) log("SPP closed: " + e.getMessage());
                } finally {
                    if (gen == sessionGen && connState == CONN_CONNECTED) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (gen == sessionGen) disconnectInternal(true);
                            }
                        });
                    }
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void connectGatt(BluetoothDevice dev, final int gen) {
        if (gen != sessionGen) return;
        useGatt = true;
        jblGattAcc = new byte[0];
        gatt = dev.connectGatt(ctx, false, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (gen != sessionGen) { try { g.close(); } catch (Exception ignored) {} return; }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("GATT connected, discovering services...");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("GATT disconnected");
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen == sessionGen) disconnectInternal(true);
                        }
                    });
                }
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (gen != sessionGen) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    main.post(new Runnable() { @Override public void run() { if (gen == sessionGen) disconnectInternal(true); } });
                    return;
                }
                BluetoothGattCharacteristic w = null, n = null;
                boolean wantJbl = Protocol.isJbl(activeModel);
                for (int pass = 0; pass < 2 && (w == null || n == null); pass++) {
                    for (BluetoothGattService svc : g.getServices()) {
                        String u = svc.getUuid().toString().toLowerCase();
                        if (u.startsWith("00001800") || u.startsWith("00001801")) continue;
                        if (wantJbl && pass == 0 && !u.equals(JBL_SERVICE_UUID)) continue;
                        BluetoothGattCharacteristic cw = null, cn = null;
                        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                            int p = c.getProperties();
                            if (cw == null && ((p & 8) != 0 || (p & 4) != 0)) cw = c;
                            if (cn == null && ((p & 16) != 0 || (p & 32) != 0)) cn = c;
                        }
                        if (cw != null && cn != null) { w = cw; n = cn; break; }
                    }
                    if (!wantJbl) break;
                }
                if (w == null || n == null) {
                    main.post(new Runnable() { @Override public void run() { if (gen == sessionGen) disconnectInternal(true); } });
                    return;
                }
                writeChar = w;
                g.setCharacteristicNotification(n, true);
                BluetoothGattDescriptor d = n.getDescriptor(CCCD_UUID);
                if (d != null) {
                    byte[] val = (n.getProperties() & 16) != 0
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, val);
                    } else {
                        d.setValue(val);
                        g.writeDescriptor(d);
                    }
                } else {
                    connState = CONN_CONNECTED;
                    notifyState();
                    onReady(gen);
                }
            }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int s) {
                if (gen != sessionGen) return;
                connState = CONN_CONNECTED;
                notifyState();
                onReady(gen);
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                if (gen != sessionGen) return;
                handleGattBytes(value);
            }
            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (gen != sessionGen) return;
                byte[] v = c.getValue();
                if (v != null) handleGattBytes(v);
            }
        }, Protocol.isJbl(activeModel) ? BluetoothDevice.TRANSPORT_LE : BluetoothDevice.TRANSPORT_AUTO);
    }

    private void handleGattBytes(byte[] value) {
        if (Protocol.isJbl(activeModel)) {
            byte[] merged = new byte[jblGattAcc.length + value.length];
            System.arraycopy(jblGattAcc, 0, merged, 0, jblGattAcc.length);
            System.arraycopy(value, 0, merged, jblGattAcc.length, value.length);
            List<ExtraProtos.JblPkt> pkts = new ArrayList<>();
            jblGattAcc = ExtraProtos.jblParseStream(merged, pkts);
            for (ExtraProtos.JblPkt pkt : pkts) handleJblPacket(pkt);
        } else {
            Protocol.Frame f = Protocol.parseSingleFrame(value);
            if (f != null) handleFrame(f);
        }
    }

    private void closeResourcesOnly() {
        main.removeCallbacks(pollTask);
        main.removeCallbacks(refreshTask);
        try { if (inStream != null) inStream.close(); } catch (Exception ignored) {}
        try { if (outStream != null) outStream.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null; inStream = null; outStream = null;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        writeChar = null;
        resetState();
    }

    void disconnect() {
        disconnectInternal(false);
    }

    private void disconnectInternal(boolean emitUnexpected) {
        sessionGen++;
        closeResourcesOnly();
        activeModel = Protocol.MODEL_UNKNOWN;
        activeMac = null;
        connState = CONN_DISCONNECTED;
        notifyState();
        if (emitUnexpected) {
            main.post(new Runnable() {
                @Override public void run() { listener.onUnexpectedDisconnect(); }
            });
        }
    }

    private void resetState() {
        synchronized (state) {
            state.leftBat = state.rightBat = state.caseBat = -1;
            state.leftChg = state.rightChg = state.caseChg = false;
            state.ancMode = -1;
            state.ancLevel = -1;
            state.gameMode = state.spatialAudio = state.hqAudio = state.dynamicAudio = null;
            state.dualConn = state.autoPlayPause = state.autoAnswer = null;
            state.eqId = -1;
            state.customEqName = null;
            state.leftUseMin = state.rightUseMin = -1;
            for (int i = 0; i < 2; i++) Arrays.fill(state.touch[i], -1);
            state.multiDevices.clear();
            hwMultiMap.clear();
            galaxyMgrSent = false;
            sonyTxSeq = 0;
            xiaomiAuthed = false;
            pbChannel = 1;
            jblGattAcc = new byte[0];
        }
    }

    private int nextSonySeq() { int s = sonyTxSeq; sonyTxSeq ^= 1; return s; }

    private void onReady(final int gen) {
        io.execute(new Runnable() {
            @Override public void run() {
                sleep(150);
                if (gen != sessionGen) return;
                log("Device ready, requesting state...");
                if (Protocol.isGalaxy(activeModel)) return;
                if (Protocol.isNothing(activeModel)) {
                    sendRaw(NothingProto.build(NothingProto.CMD_HANDSHAKE_REQ, null));
                    sleep(100);
                } else if (Protocol.isXiaomi(activeModel)) {
                    sendRaw(ExtraProtos.xiaomiAuthChallenge());
                    sleep(150);
                } else if (Protocol.isPixelBuds(activeModel)) {
                    sendRaw(ExtraProtos.pbRpc(pbChannel, ExtraProtos.PB_M_SUB_BAT, null)); sleep(80);
                    sendRaw(ExtraProtos.pbRpc(pbChannel, ExtraProtos.PB_M_SUB_SET, null)); sleep(80);
                }
                requestFullState(true);
                if (Protocol.isRealme(activeModel)) {
                    byte[] evs = Protocol.supportsDual(activeModel)
                        ? new byte[]{0x03, 0x05, 0x01, 0x02, 0x06}
                        : new byte[]{0x03, 0x05, 0x01, 0x02};
                    for (byte e : evs) {
                        sendTl(Protocol.CMD_NOTIFY_REG, new byte[]{e});
                        sleep(50);
                    }
                }
                main.postDelayed(pollTask, 2000L);
            }
        });
    }

    void requestFullState(final boolean includeUseTime) {
        io.execute(new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED) return;
                if (Protocol.isGalaxy(activeModel)) return;
                if (Protocol.isHuawei(activeModel) || Protocol.isHonorChoice(activeModel)) {
                    if (Protocol.supportsAnc(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_ANC_R, 1, 2)); sleep(70); }
                    if (Protocol.isHuawei(activeModel)) {
                        if (Protocol.supportsGameMode(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_LOW_LAT, 2)); sleep(70); }
                        if (Protocol.supportsHqAudio(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_SQP_R, 1)); sleep(70); }
                        if (Protocol.supportsDual(activeModel)) {
                            sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_DC_R, 1)); sleep(70);
                            hwMultiMap.clear();
                            sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_DC_ENUM, 1)); sleep(70);
                        }
                        if (Protocol.supportsAutoPlayPause(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_AUTOPAUSE_R, 1)); sleep(70); }
                        if (Protocol.supportsEq(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_EQ_R, 1, 2, 3, 4, 5, 6, 7, 8)); sleep(70); }
                    }
                    sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_BAT_READ, 1, 2, 3)); sleep(70);
                    if (Protocol.isHuawei(activeModel)) {
                        if (Protocol.supportsDoubleTap(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_DTAP_R, 1, 2)); sleep(70); }
                        if (Protocol.supportsTripleTap(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_TTAP_R, 1, 2)); sleep(70); }
                        if (Protocol.supportsLongTap(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_LTAP_R, 1, 2)); }
                    }
                    return;
                }
                if (Protocol.isNothing(activeModel)) {
                    if (Protocol.supportsAnc(activeModel)) { sendRaw(NothingProto.build(NothingProto.CMD_ANC_REQ, null)); sleep(70); }
                    sendRaw(NothingProto.build(Protocol.isNothingListeningEq(activeModel) ? NothingProto.CMD_EQ_L_REQ : NothingProto.CMD_EQ_C_REQ, null)); sleep(70);
                    sendRaw(NothingProto.build(NothingProto.CMD_INEAR_REQ, null)); sleep(70);
                    sendRaw(NothingProto.build(NothingProto.CMD_LAT_REQ, null)); sleep(70);
                    sendRaw(NothingProto.build(NothingProto.CMD_BASS_REQ, null)); sleep(70);
                    sendRaw(NothingProto.build(NothingProto.CMD_BAT_REQ, null));
                    return;
                }
                if (Protocol.isSony(activeModel)) {
                    sendRaw(ExtraProtos.sonyBatReq(nextSonySeq(), (byte) 0x00)); sleep(90);
                    sendRaw(ExtraProtos.sonyBatReq(nextSonySeq(), (byte) 0x01)); sleep(90);
                    sendRaw(ExtraProtos.sonyBatReq(nextSonySeq(), (byte) 0x02)); sleep(90);
                    sendRaw(ExtraProtos.sonyAncReq(nextSonySeq())); sleep(90);
                    sendRaw(ExtraProtos.sonyEqReq(nextSonySeq()));
                    return;
                }
                if (Protocol.isBose(activeModel)) {
                    sendRaw(ExtraProtos.boseBuild(0x02, 0x02, 0x01, null)); sleep(70);
                    sendRaw(ExtraProtos.boseBuild(0x01, 0x06, 0x01, null)); sleep(70);
                    sendRaw(ExtraProtos.boseBuild(0x01, 0x07, 0x01, null));
                    return;
                }
                if (Protocol.isSoundcore(activeModel)) {
                    sendRaw(ExtraProtos.scBuild(0x01, 0x01, null)); sleep(70);
                    sendRaw(ExtraProtos.scBuild(0x06, 0x01, null)); sleep(70);
                    sendRaw(ExtraProtos.scBuild(0x01, 0x03, null));
                    return;
                }
                if (Protocol.isXiaomi(activeModel)) {
                    if (!xiaomiAuthed) { sendRaw(ExtraProtos.xiaomiAuthChallenge()); return; }
                    sendRaw(ExtraProtos.xiaomiReq(0x03, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})); sleep(70);
                    sendRaw(ExtraProtos.xiaomiReq(0x07, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})); sleep(70);
                    sendRaw(ExtraProtos.xiaomiGetCfg(0x2F)); sleep(70);
                    sendRaw(ExtraProtos.xiaomiGetCfg(0x04)); sleep(70);
                    sendRaw(ExtraProtos.xiaomiGetCfg(0x07)); sleep(70);
                    sendRaw(ExtraProtos.xiaomiGetCfg(0x03));
                    return;
                }
                if (Protocol.isSennheiser(activeModel)) {
                    sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x06, 0, 0x02, null)); sleep(70);
                    sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x05, 0, 0x01, null)); sleep(70);
                    sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x06, 0, 0x06, null));
                    return;
                }
                if (Protocol.isJabra(activeModel)) {
                    sendRaw(ExtraProtos.jabraBuild(0x10, 0x0101, null)); sleep(70);
                    sendRaw(ExtraProtos.jabraBuild(0x10, 0x0104, null)); sleep(70);
                    sendRaw(ExtraProtos.jabraBuild(0x10, 0x0107, null));
                    return;
                }
                if (Protocol.isPixelBuds(activeModel)) {
                    sendRaw(ExtraProtos.pbReadSetting(pbChannel, 11)); sleep(70);
                    sendRaw(ExtraProtos.pbReadSetting(pbChannel, 5)); sleep(70);
                    sendRaw(ExtraProtos.pbReadSetting(pbChannel, 21));
                    return;
                }
                if (Protocol.isJbl(activeModel)) {
                    sendRaw(ExtraProtos.jblBuild(0x25, null)); sleep(70);
                    sendRaw(ExtraProtos.jblBuild(0x91, new byte[]{0x11})); sleep(70);
                    sendRaw(ExtraProtos.jblBuild(0x21, new byte[]{0x35})); sleep(70);
                    sendRaw(ExtraProtos.jblBuild(0x21, new byte[]{0x40})); sleep(70);
                    sendRaw(ExtraProtos.jblBuild(0x21, new byte[]{(byte) 0x9D}));
                    return;
                }
                sendTl(Protocol.CMD_ANC_STATE_REQ, new byte[]{0x01, 0x01}); sleep(70);
                sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_GAME_MODE}); sleep(70);
                sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_SPATIAL}); sleep(70);
                if (Protocol.supportsHqAudio(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_HQ_AUDIO}); sleep(70);
                }
                if (Protocol.supportsDynamic(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_DYNAMIC}); sleep(70);
                }
                if (Protocol.supportsDual(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_DUAL_CONN}); sleep(70);
                    sendTl(Protocol.CMD_MULTI_REQ, null); sleep(70);
                }
                if (Protocol.supportsAutoPlayPause(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_AUTO_PLAY_PAUSE}); sleep(70);
                }
                if (Protocol.supportsAutoAnswer(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_AUTO_ANSWER}); sleep(70);
                }
                sendTl(Protocol.CMD_EQ_REQ, null); sleep(70);
                sendTl(Protocol.CMD_ALL_EQ_REQ, null); sleep(70);
                sendTl(Protocol.CMD_BATTERY_REQ, null); sleep(70);
                sendTl(Protocol.CMD_KEY_REQ, Protocol.KEY_REQ_PAYLOAD); sleep(70);
                if (includeUseTime) sendTl(Protocol.CMD_USE_TIME_REQ, null);
            }
        });
    }

    private void pollCycle() {
        io.execute(new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED || Protocol.isGalaxy(activeModel) || Protocol.isPixelBuds(activeModel)) return;
                if (Protocol.isHuawei(activeModel) || Protocol.isHonorChoice(activeModel)) {
                    if (Protocol.supportsAnc(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_ANC_R, 1, 2)); sleep(55); }
                    if (Protocol.isHuawei(activeModel)) {
                        if (Protocol.supportsGameMode(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_LOW_LAT, 2)); sleep(55); }
                        if (Protocol.supportsHqAudio(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_SQP_R, 1)); sleep(55); }
                        if (Protocol.supportsDual(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_DC_R, 1)); sleep(55); }
                        if (Protocol.supportsAutoPlayPause(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_AUTOPAUSE_R, 1)); sleep(55); }
                        if (Protocol.supportsEq(activeModel)) { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_EQ_R, 1, 2, 3, 4, 5, 6, 7, 8)); sleep(55); }
                    }
                    sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_BAT_READ, 1, 2, 3));
                    return;
                }
                if (Protocol.isNothing(activeModel)) {
                    if (Protocol.supportsAnc(activeModel)) { sendRaw(NothingProto.build(NothingProto.CMD_ANC_REQ, null)); sleep(55); }
                    sendRaw(NothingProto.build(NothingProto.CMD_BAT_REQ, null));
                    return;
                }
                if (Protocol.isSony(activeModel)) {
                    sendRaw(ExtraProtos.sonyBatReq(nextSonySeq(), (byte) 0x01)); sleep(80);
                    sendRaw(ExtraProtos.sonyBatReq(nextSonySeq(), (byte) 0x02));
                    return;
                }
                if (Protocol.isBose(activeModel)) {
                    sendRaw(ExtraProtos.boseBuild(0x02, 0x02, 0x01, null));
                    return;
                }
                if (Protocol.isSoundcore(activeModel)) {
                    sendRaw(ExtraProtos.scBuild(0x01, 0x03, null));
                    return;
                }
                if (Protocol.isXiaomi(activeModel)) {
                    if (xiaomiAuthed) sendRaw(ExtraProtos.xiaomiReq(0x03, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
                    return;
                }
                if (Protocol.isSennheiser(activeModel)) {
                    sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x06, 0, 0x02, null));
                    return;
                }
                if (Protocol.isJabra(activeModel)) {
                    sendRaw(ExtraProtos.jabraBuild(0x10, 0x0101, null));
                    return;
                }
                if (Protocol.isJbl(activeModel)) {
                    sendRaw(ExtraProtos.jblBuild(0x25, null));
                    return;
                }
                sendTl(Protocol.CMD_ANC_STATE_REQ, new byte[]{0x01, 0x01}); sleep(55);
                sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_GAME_MODE}); sleep(55);
                sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_SPATIAL}); sleep(55);
                if (Protocol.supportsHqAudio(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_HQ_AUDIO}); sleep(55);
                }
                if (Protocol.supportsDynamic(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_DYNAMIC}); sleep(55);
                }
                if (Protocol.supportsDual(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_DUAL_CONN}); sleep(55);
                }
                if (Protocol.supportsAutoPlayPause(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_AUTO_PLAY_PAUSE}); sleep(55);
                }
                if (Protocol.supportsAutoAnswer(activeModel)) {
                    sendTl(Protocol.CMD_SWITCH_REQ, new byte[]{0x01, Protocol.FEAT_AUTO_ANSWER}); sleep(55);
                }
                sendTl(Protocol.CMD_EQ_REQ, null); sleep(55);
                sendTl(Protocol.CMD_BATTERY_REQ, null);
            }
        });
    }

    void setAnc(final byte mode) {
        synchronized (state) { state.ancMode = mode & 0xFF; }
        notifyState();
        io.execute(new Runnable() {
            @Override public void run() {
                if (Protocol.isHuawei(activeModel)) {
                    log("TX Huawei ANC [" + Protocol.ancName(mode & 0xFF) + "]");
                    sendRaw(HuaweiProto.writeAncMode(mode));
                    sleep(180);
                    sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_ANC_R, 1, 2));
                    return;
                }
                if (Protocol.isHonorChoice(activeModel)) {
                    log("TX HonorChoice ANC [" + Protocol.ancName(mode & 0xFF) + "]");
                    sendRaw(HuaweiProto.writeHonorAnc(mode));
                    sleep(180);
                    sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_ANC_R, 1, 2));
                    return;
                }
                if (Protocol.isNothing(activeModel)) {
                    log("TX Nothing ANC [" + Protocol.ancName(mode & 0xFF) + "]");
                    sendRaw(NothingProto.writeAncMode(mode, state.ancLevel));
                    sleep(180);
                    sendRaw(NothingProto.build(NothingProto.CMD_ANC_REQ, null));
                    return;
                }
                if (Protocol.isGalaxy(activeModel)) {
                    log("TX Galaxy ANC [" + Protocol.ancName(mode & 0xFF) + "]");
                    sendRaw(GalaxyProto.writeAnc(mode, activeModel));
                    return;
                }
                if (Protocol.isSony(activeModel)) {
                    sendRaw(ExtraProtos.sonyAncSet(nextSonySeq(), mode));
                    return;
                }
                if (Protocol.isBose(activeModel)) {
                    sendRaw(ExtraProtos.boseAncSet(mode));
                    return;
                }
                if (Protocol.isSoundcore(activeModel)) {
                    sendRaw(ExtraProtos.scAncSet(mode));
                    return;
                }
                if (Protocol.isXiaomi(activeModel)) {
                    sendRaw(ExtraProtos.xiaomiAncSet(mode));
                    return;
                }
                if (Protocol.isSennheiser(activeModel)) {
                    if (mode == Protocol.ANC_OFF) {
                        sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x05, 0, 0x02, new byte[]{0}));
                    } else if (mode == Protocol.ANC_ON) {
                        sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x05, 0, 0x02, new byte[]{1})); sleep(60);
                        sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x05, 0, 0x04, new byte[]{0}));
                    } else {
                        sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x05, 0, 0x02, new byte[]{1})); sleep(60);
                        sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x05, 0, 0x04, new byte[]{1}));
                    }
                    return;
                }
                if (Protocol.isJabra(activeModel)) {
                    sendRaw(ExtraProtos.jabraAncSet(mode));
                    return;
                }
                if (Protocol.isPixelBuds(activeModel)) {
                    long v = mode == Protocol.ANC_ON ? 2L : (mode == Protocol.ANC_TRANSPARENCY ? 3L : 1L);
                    sendRaw(ExtraProtos.pbWriteSetting(pbChannel, 11, v));
                    return;
                }
                if (Protocol.isJbl(activeModel)) {
                    sendRaw(ExtraProtos.jblAncSet(mode));
                    return;
                }
                log("TX ANC [" + Protocol.ancName(mode & 0xFF) + "]");
                sendTl(Protocol.CMD_ANC_SET, new byte[]{0x01, 0x01, mode});
            }
        });
    }

    void setAncLevel(final byte lvl) {
        if (!Protocol.supportsAncLevel(activeModel)) return;
        synchronized (state) { state.ancMode = Protocol.ANC_ON; state.ancLevel = lvl & 0xFF; }
        notifyState();
        io.execute(new Runnable() {
            @Override public void run() {
                if (Protocol.isNothing(activeModel)) {
                    log("TX Nothing ANC Level [" + (lvl & 0xFF) + "]");
                    sendRaw(NothingProto.writeAncLevel(lvl));
                    sleep(180);
                    sendRaw(NothingProto.build(NothingProto.CMD_ANC_REQ, null));
                    return;
                }
                if (Protocol.isXiaomi(activeModel)) {
                    sendRaw(ExtraProtos.xiaomiAncLevelSet(lvl));
                    return;
                }
                log("TX Huawei ANC Level [" + (lvl & 0xFF) + "]");
                sendRaw(HuaweiProto.writeAncLevel(lvl));
                sleep(180);
                sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_ANC_R, 1, 2));
            }
        });
    }

    void setEq(final byte eqId) {
        synchronized (state) { state.eqId = eqId & 0xFF; state.customEqName = null; }
        notifyState();
        io.execute(new Runnable() {
            @Override public void run() {
                if (Protocol.isHuawei(activeModel)) {
                    byte hw = HuaweiProto.eqToHw(eqId, Protocol.hwEqPro3(activeModel));
                    log("TX Huawei EQ [id=" + hw + "]");
                    sendRaw(HuaweiProto.writeByte(HuaweiProto.CMD_EQ_W, 1, hw));
                    sleep(180);
                    sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_EQ_R, 1, 2, 3, 4, 5, 6, 7, 8));
                    return;
                }
                if (Protocol.isHonorChoice(activeModel)) {
                    log("TX HonorChoice EQ [id=" + (eqId & 0xFF) + "]");
                    sendRaw(HuaweiProto.writeHonorEq(eqId));
                    return;
                }
                if (Protocol.isNothing(activeModel)) {
                    boolean listening = Protocol.isNothingListeningEq(activeModel);
                    log("TX Nothing EQ [id=" + (eqId & 0xFF) + "]");
                    sendRaw(NothingProto.writeEq(eqId, listening));
                    sleep(180);
                    sendRaw(NothingProto.build(listening ? NothingProto.CMD_EQ_L_REQ : NothingProto.CMD_EQ_C_REQ, null));
                    return;
                }
                if (Protocol.isGalaxy(activeModel)) {
                    log("TX Galaxy EQ [id=" + (eqId & 0xFF) + "]");
                    sendRaw(GalaxyProto.writeEq(eqId, activeModel == Protocol.MODEL_GALAXY_BUDS));
                    return;
                }
                if (Protocol.isSony(activeModel)) {
                    sendRaw(ExtraProtos.sonyEqSet(nextSonySeq(), eqId));
                    return;
                }
                if (Protocol.isXiaomi(activeModel)) {
                    sendRaw(ExtraProtos.xiaomiEqSet(eqId));
                    return;
                }
                if (Protocol.isJbl(activeModel)) {
                    sendRaw(ExtraProtos.jblEqSet(eqId));
                    return;
                }
                log("TX EQ [id=" + (eqId & 0xFF) + "]");
                sendTl(Protocol.CMD_EQ_SET, new byte[]{eqId});
            }
        });
    }

    void setSwitch(final byte featId, final boolean on) {
        synchronized (state) {
            if (featId == Protocol.FEAT_GAME_MODE) state.gameMode = on;
            else if (featId == Protocol.FEAT_SPATIAL) state.spatialAudio = on;
            else if (featId == Protocol.FEAT_HQ_AUDIO) state.hqAudio = on;
            else if (featId == Protocol.FEAT_DYNAMIC) state.dynamicAudio = on;
            else if (featId == Protocol.FEAT_DUAL_CONN) state.dualConn = on;
            else if (featId == Protocol.FEAT_AUTO_PLAY_PAUSE) state.autoPlayPause = on;
            else if (featId == Protocol.FEAT_AUTO_ANSWER) state.autoAnswer = on;
        }
        notifyState();
        io.execute(new Runnable() {
            @Override public void run() {
                byte v = (byte) (on ? 1 : 0);
                if (Protocol.isHuawei(activeModel)) {
                    if (featId == Protocol.FEAT_GAME_MODE) {
                        sendRaw(HuaweiProto.writeByte(HuaweiProto.CMD_LOW_LAT, 1, v));
                        sleep(400);
                        sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_LOW_LAT, 2));
                    } else if (featId == Protocol.FEAT_HQ_AUDIO) {
                        sendRaw(HuaweiProto.writeByte(HuaweiProto.CMD_SQP_W, 1, v));
                        sleep(180);
                        sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_SQP_R, 1));
                    } else if (featId == Protocol.FEAT_DUAL_CONN) {
                        sendRaw(HuaweiProto.writeByte(HuaweiProto.CMD_DC_W, 1, v));
                        sleep(200);
                        sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_DC_R, 1));
                    } else if (featId == Protocol.FEAT_AUTO_PLAY_PAUSE) {
                        sendRaw(HuaweiProto.writeByte(HuaweiProto.CMD_AUTOPAUSE_W, 1, v));
                        sleep(180);
                        sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_AUTOPAUSE_R, 1));
                    }
                    return;
                }
                if (Protocol.isNothing(activeModel)) {
                    if (featId == Protocol.FEAT_GAME_MODE) {
                        sendRaw(NothingProto.build(NothingProto.CMD_LAT_SET, new byte[]{on ? (byte) 1 : (byte) 2}));
                        sleep(200);
                        sendRaw(NothingProto.build(NothingProto.CMD_LAT_REQ, null));
                    } else if (featId == Protocol.FEAT_AUTO_PLAY_PAUSE) {
                        sendRaw(NothingProto.build(NothingProto.CMD_INEAR_SET, new byte[]{1, 1, v}));
                        sleep(200);
                        sendRaw(NothingProto.build(NothingProto.CMD_INEAR_REQ, null));
                    } else if (featId == Protocol.FEAT_DYNAMIC) {
                        sendRaw(NothingProto.build(NothingProto.CMD_BASS_SET, new byte[]{v, (byte) nothingBassLvl}));
                        sleep(200);
                        sendRaw(NothingProto.build(NothingProto.CMD_BASS_REQ, null));
                    }
                    return;
                }
                if (Protocol.isGalaxy(activeModel)) {
                    boolean leg = activeModel == Protocol.MODEL_GALAXY_BUDS;
                    if (featId == Protocol.FEAT_DUAL_CONN) {
                        sendRaw(GalaxyProto.build(GalaxyProto.MSG_SEAMLESS_SET, new byte[]{(byte) (on ? 0 : 1)}, leg));
                    } else if (featId == Protocol.FEAT_GAME_MODE) {
                        sendRaw(GalaxyProto.build(0x87, new byte[]{v}, leg));
                    }
                    return;
                }
                if (Protocol.isBose(activeModel)) {
                    if (featId == Protocol.FEAT_DUAL_CONN) sendRaw(ExtraProtos.boseBuild(0x01, 0x07, 0x02, new byte[]{v}));
                    return;
                }
                if (Protocol.isSoundcore(activeModel)) {
                    if (featId == Protocol.FEAT_GAME_MODE) sendRaw(ExtraProtos.scBuild(0x01, 0x87, new byte[]{v}));
                    return;
                }
                if (Protocol.isXiaomi(activeModel)) {
                    if (featId == Protocol.FEAT_GAME_MODE) {
                        sendRaw(ExtraProtos.xiaomiSetCfg(0x2F, new byte[]{v}));
                        sleep(200);
                        sendRaw(ExtraProtos.xiaomiGetCfg(0x2F));
                    } else if (featId == Protocol.FEAT_DUAL_CONN) sendRaw(ExtraProtos.xiaomiSetCfg(0x04, new byte[]{v}));
                    else if (featId == Protocol.FEAT_AUTO_ANSWER) sendRaw(ExtraProtos.xiaomiSetCfg(0x03, new byte[]{v}));
                    else if (featId == Protocol.FEAT_AUTO_PLAY_PAUSE) sendRaw(ExtraProtos.xiaomiReq(0x08, new byte[]{0x02, 0x06, (byte) (on ? 0x00 : 0x01)}));
                    return;
                }
                if (Protocol.isSennheiser(activeModel)) {
                    if (featId == Protocol.FEAT_SPATIAL) sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x06, 0, 0x06, new byte[]{v}));
                    return;
                }
                if (Protocol.isJabra(activeModel)) {
                    if (featId == Protocol.FEAT_DUAL_CONN) sendRaw(ExtraProtos.jabraBuild(0x20, 0x0107, new byte[]{v}));
                    return;
                }
                if (Protocol.isPixelBuds(activeModel)) {
                    if (featId == Protocol.FEAT_AUTO_PLAY_PAUSE) sendRaw(ExtraProtos.pbWriteSetting(pbChannel, 5, on ? 1L : 0L));
                    else if (featId == Protocol.FEAT_DUAL_CONN) sendRaw(ExtraProtos.pbWriteSetting(pbChannel, 21, on ? 1L : 0L));
                    return;
                }
                if (Protocol.isJbl(activeModel)) {
                    if (featId == Protocol.FEAT_AUTO_PLAY_PAUSE) sendRaw(ExtraProtos.jblBuild(0x35, new byte[]{v}));
                    else if (featId == Protocol.FEAT_SPATIAL) sendRaw(ExtraProtos.jblBuild(0x9D, new byte[]{0x01, v, 0x01}));
                    return;
                }
                log(String.format("TX Switch 0x%02X -> %s", featId & 0xFF, on ? "ON" : "OFF"));
                sendTl(Protocol.CMD_SWITCH_SET, new byte[]{featId, v});
            }
        });
    }

    void setKey(final int sideIdx, final int gestureIdx, final byte funcId) {
        synchronized (state) { state.touch[sideIdx][gestureIdx] = funcId & 0xFF; }
        notifyState();
        final byte devType = (byte) (sideIdx == 0 ? 0x01 : 0x02);
        final byte actionId = (byte) (gestureIdx + 2);
        io.execute(new Runnable() {
            @Override public void run() {
                if (Protocol.isHuawei(activeModel)) {
                    int cmd = gestureIdx == 0 ? HuaweiProto.CMD_DTAP_W
                        : (gestureIdx == 1 ? HuaweiProto.CMD_TTAP_W : HuaweiProto.CMD_LTAP_W);
                    byte code = HuaweiProto.fnToHwTap(funcId, gestureIdx, activeModel == Protocol.MODEL_HW_3);
                    sendRaw(HuaweiProto.writeByte(cmd, sideIdx == 0 ? 1 : 2, code));
                    return;
                }
                log(String.format("TX Key [side=%d act=%d -> 0x%02X]", devType, actionId, funcId & 0xFF));
                sendTl(Protocol.CMD_KEY_SET, new byte[]{0x01, devType, 0x01, actionId, funcId});
            }
        });
    }

    void setHardwareFindBuds(final boolean on, final int ch) {
        io.execute(new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED) return;
                if (Protocol.isRealme(activeModel)) {
                    sendTl(Protocol.CMD_FIND_BUDS, new byte[]{(byte) (on ? 1 : 0)});
                } else if (Protocol.isNothing(activeModel)) {
                    byte side = (byte) (ch == FindTone.CH_LEFT ? 0x01 : (ch == FindTone.CH_RIGHT ? 0x03 : 0x06));
                    sendRaw(NothingProto.build(0xF002, new byte[]{side, (byte) (on ? 1 : 0)}));
                } else if (Protocol.isGalaxy(activeModel)) {
                    sendRaw(GalaxyProto.build(on ? 0xA0 : 0xA1, new byte[0], activeModel == Protocol.MODEL_GALAXY_BUDS));
                }
            }
        });
    }

    boolean renameFirmware(final String newName) {
        if (connState != CONN_CONNECTED || !Protocol.isBose(activeModel)) return false;
        io.execute(new Runnable() {
            @Override public void run() { sendRaw(ExtraProtos.boseRename(newName)); }
        });
        return true;
    }

    void setCustomEq(final int[] gains) {
        synchronized (state) { state.eqId = -1; state.customEqName = "Custom"; }
        notifyState();
        io.execute(new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED) return;
                if (Protocol.isRealme(activeModel)) {
                    int[] freqs = {62, 250, 1000, 4000, 8000, 16000};
                    byte[] nm = "Custom".getBytes(StandardCharsets.UTF_8);
                    byte[] p = new byte[6 + nm.length + freqs.length * 3];
                    p[0] = 2; p[1] = -6; p[2] = 6; p[3] = 1; p[4] = (byte) nm.length;
                    System.arraycopy(nm, 0, p, 5, nm.length);
                    int o = 5 + nm.length;
                    p[o++] = (byte) freqs.length;
                    for (int i = 0; i < freqs.length; i++) {
                        int f = freqs[i];
                        int g = Math.max(-6, Math.min(6, i < gains.length ? gains[i] : 0));
                        p[o++] = (byte) (f & 0xFF);
                        p[o++] = (byte) ((f >>> 8) & 0xFF);
                        p[o++] = (byte) g;
                    }
                    sendTl(Protocol.CMD_CUSTOM_EQ_SET, p);
                } else if (Protocol.isXiaomi(activeModel)) {
                    int[] freqs = {62, 125, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};
                    sendRaw(ExtraProtos.xiaomiCustomEqSave(freqs, gains));
                } else if (Protocol.isSony(activeModel)) {
                    sendRaw(ExtraProtos.sonyCustomEqSet(nextSonySeq(), gains));
                } else if (Protocol.isBose(activeModel)) {
                    for (int i = 0; i < Math.min(3, gains.length); i++) {
                        sendRaw(ExtraProtos.boseEqBandSet(i, gains[i]));
                        sleep(50);
                    }
                } else if (Protocol.isSoundcore(activeModel)) {
                    sendRaw(ExtraProtos.scCustomEqSet(gains));
                } else if (Protocol.isHuawei(activeModel)) {
                    byte[] data = new byte[10];
                    for (int i = 0; i < 10; i++) {
                        int g = Math.max(-6, Math.min(6, i < gains.length ? gains[i] : 0));
                        data[i] = (byte) (g * 10);
                    }
                    sendRaw(HuaweiProto.build(HuaweiProto.CMD_EQ_W,
                        new int[]{1, 2, 3, 4, 5},
                        new byte[][]{new byte[]{11}, new byte[]{10}, data, "Custom".getBytes(StandardCharsets.UTF_8), new byte[]{1}}));
                } else if (Protocol.isNothing(activeModel)) {
                    float bass = Math.max(-6, Math.min(6, gains.length > 0 ? gains[0] : 0));
                    float mid = Math.max(-6, Math.min(6, gains.length > 1 ? gains[1] : 0));
                    float trb = Math.max(-6, Math.min(6, gains.length > 2 ? gains[2] : 0));
                    float tot = -Math.max(0f, Math.max(bass, Math.max(mid, trb)));
                    byte[] pkt = new byte[1 + 4 + 3 * 13];
                    pkt[0] = 3;
                    putFloatLe(pkt, 1, tot);
                    byte[] ft = {1, 2, 0}; float[] gs = {mid, trb, bass}; float[] fs = {1000f, 6000f, 100f}; float[] qs = {1.0f, 0.707f, 0.707f};
                    for (int i = 0; i < 3; i++) {
                        int o = 5 + i * 13;
                        pkt[o] = ft[i]; putFloatLe(pkt, o + 1, gs[i]); putFloatLe(pkt, o + 5, fs[i]); putFloatLe(pkt, o + 9, qs[i]);
                    }
                    sendRaw(NothingProto.build(0xF041, pkt));
                    sleep(100);
                    boolean list = Protocol.isNothingListeningEq(activeModel);
                    sendRaw(NothingProto.build(list ? NothingProto.CMD_EQ_L_SET : NothingProto.CMD_EQ_C_SET,
                        new byte[]{(byte) (list ? 6 : 5), 0}));
                } else if (Protocol.isSennheiser(activeModel)) {
                    for (int i = 0; i < Math.min(5, gains.length); i++) {
                        int g = Math.max(-6, Math.min(6, gains[i]));
                        sendRaw(ExtraProtos.gaiaBuild(0x0495, 0x08, 0, 0x01, new byte[]{(byte) i, (byte) (g * 10)}));
                        sleep(50);
                    }
                }
            }
        });
    }

    private static void putFloatLe(byte[] a, int o, float v) {
        int b = Float.floatToRawIntBits(v);
        a[o] = (byte) (b & 0xFF); a[o + 1] = (byte) ((b >>> 8) & 0xFF);
        a[o + 2] = (byte) ((b >>> 16) & 0xFF); a[o + 3] = (byte) ((b >>> 24) & 0xFF);
    }

    private void sendTl(int cmd, byte[] payload) {
        sendRaw(Protocol.wrapOppo(Protocol.buildFrame(cmd, payload)));
    }

    private void sendRaw(byte[] wire) {
        log("TX Wire [" + Protocol.hex(wire) + "]");
        synchronized (writeLock) {
            try {
                if (!useGatt) {
                    if (outStream != null && socket != null && socket.isConnected()) {
                        outStream.write(wire);
                        outStream.flush();
                    }
                } else if (gatt != null && writeChar != null) {
                    int wt = (writeChar.getProperties() & 4) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(writeChar, wire, wt);
                    } else {
                        writeChar.setWriteType(wt);
                        writeChar.setValue(wire);
                        gatt.writeCharacteristic(writeChar);
                    }
                    sleep(35);
                }
            } catch (Exception e) {
                log("TX error: " + e.getMessage());
            }
        }
    }

    private void handleFrame(Protocol.Frame f) {
        byte[] p = f.payload;
        switch (f.cmd) {
            case Protocol.CMD_BATTERY_RSP: {
                if (p.length < 2 || p[0] != 0) break;
                int count = p[1] & 0xFF;
                int l = -1, r = -1, c = -1;
                boolean lc = false, rc = false, cc = false;
                StringBuilder sb = new StringBuilder();
                for (int i = 0, off = 2; i < count && off + 1 < p.length; i++, off += 2) {
                    int id = p[off] & 0xFF;
                    int val = p[off + 1] & 0xFF;
                    int lvl = val & 0x7F;
                    boolean chg = (val & 0x80) != 0;
                    if (id == 3 && lvl == 0 && !chg) lvl = -1; // offline case
                    if (id == 1) { l = lvl; lc = chg; }
                    else if (id == 2) { r = lvl; rc = chg; }
                    else if (id == 3) { c = lvl; cc = chg; }
                    if (lvl >= 0) {
                        if (sb.length() > 0) sb.append(", ");
                        String nm = id == 1 ? "Left" : (id == 2 ? "Right" : "Case");
                        sb.append(nm).append(": ").append(lvl).append('%').append(chg ? " ⚡" : "");
                    }
                }
                log("RX Battery [" + Protocol.hex(p) + "] -> [" + sb + "]");
                synchronized (state) {
                    state.leftBat = l; state.leftChg = lc;
                    state.rightBat = r; state.rightChg = rc;
                    state.caseBat = c; state.caseChg = cc;
                }
                notifyState();
                break;
            }
            case Protocol.CMD_ANC_STATE_RSP: {
                if (p.length >= 4 && p[0] == 0 && p[1] == 1) {
                    int m = p[3] & 0xFF;
                    if (m == Protocol.ANC_ON || m == Protocol.ANC_OFF || m == Protocol.ANC_TRANSPARENCY) {
                        log("RX ANC State: " + Protocol.ancName(m));
                        synchronized (state) { state.ancMode = m; }
                        notifyState();
                    }
                }
                break;
            }
            case Protocol.CMD_ANC_RSP: {
                if (p.length > 0 && p[0] == 0) {
                    io.execute(new Runnable() {
                        @Override public void run() { sendTl(Protocol.CMD_ANC_STATE_REQ, new byte[]{0x01, 0x01}); }
                    });
                }
                break;
            }
            case Protocol.CMD_SWITCH_RSP: {
                Boolean gm = Protocol.parseSwitch(p, Protocol.FEAT_GAME_MODE);
                Boolean sa = Protocol.parseSwitch(p, Protocol.FEAT_SPATIAL);
                Boolean hq = Protocol.parseSwitch(p, Protocol.FEAT_HQ_AUDIO);
                Boolean da = Protocol.parseSwitch(p, Protocol.FEAT_DYNAMIC);
                Boolean dc = Protocol.parseSwitch(p, Protocol.FEAT_DUAL_CONN);
                Boolean ap = Protocol.parseSwitch(p, Protocol.FEAT_AUTO_PLAY_PAUSE);
                Boolean aa = Protocol.parseSwitch(p, Protocol.FEAT_AUTO_ANSWER);
                synchronized (state) {
                    if (gm != null) { state.gameMode = gm; log("RX GameMode: " + (gm ? "ON" : "OFF")); }
                    if (sa != null) { state.spatialAudio = sa; log("RX SpatialAudio: " + (sa ? "ON" : "OFF")); }
                    if (hq != null) { state.hqAudio = hq; log("RX HQ Audio: " + (hq ? "ON" : "OFF")); }
                    if (da != null) { state.dynamicAudio = da; log("RX DynamicAudio: " + (da ? "ON" : "OFF")); }
                    if (dc != null) { state.dualConn = dc; log("RX DualConn: " + (dc ? "ON" : "OFF")); }
                    if (ap != null) { state.autoPlayPause = ap; log("RX AutoPlayPause: " + (ap ? "ON" : "OFF")); }
                    if (aa != null) { state.autoAnswer = aa; log("RX AutoAnswer: " + (aa ? "ON" : "OFF")); }
                }
                notifyState();
                break;
            }
            case Protocol.CMD_SWITCH_SET_RSP: {
                if (p.length > 0 && p[0] == 0) {
                    scheduleRefresh();
                }
                break;
            }
            case Protocol.CMD_EQ_RSP: {
                if (p.length >= 2 && p[0] == 0) {
                    int id = p[1] & 0xFF;
                    synchronized (state) {
                        if (id <= 3) {
                            state.eqId = id;
                            state.customEqName = null;
                        } else {
                            state.eqId = -1;
                            io.execute(new Runnable() {
                                @Override public void run() { sendTl(Protocol.CMD_ALL_EQ_REQ, null); }
                            });
                        }
                    }
                    notifyState();
                }
                break;
            }
            case Protocol.CMD_EQ_SET_RSP: {
                if (p.length > 0 && p[0] == 0) {
                    io.execute(new Runnable() {
                        @Override public void run() { sendTl(Protocol.CMD_EQ_REQ, null); }
                    });
                }
                break;
            }
            case Protocol.CMD_ALL_EQ_RSP: {
                if (p.length < 2 || p[0] != 0) break;
                int count = p[1] & 0xFF, off = 2;
                String active = null;
                for (int i = 0; i < count && off + 5 <= p.length; i++) {
                    boolean sel = (p[off] & 0xFF) == 1;
                    int nLen = p[off + 4] & 0xFF;
                    off += 5;
                    if (off + nLen > p.length) break;
                    String name = new String(p, off, nLen, StandardCharsets.UTF_8);
                    off += nLen;
                    if (off < p.length) {
                        int fc = p[off] & 0xFF;
                        off += 1 + fc * 3;
                    }
                    if (sel) active = name;
                }
                if (active != null) {
                    synchronized (state) { state.eqId = -1; state.customEqName = active; }
                    notifyState();
                }
                break;
            }
            case Protocol.CMD_KEY_RSP: {
                if (p.length < 2 || p[0] != 0) break;
                int count = p[1] & 0xFF;
                if (p.length < 2 + count * 4) break;
                synchronized (state) {
                    for (int i = 0, off = 2; i < count; i++, off += 4) {
                        int side = (p[off] & 0xFF) - 1;
                        int gest = (p[off + 2] & 0xFF) - 2;
                        int fn = p[off + 3] & 0xFF;
                        if (fn == 4) fn = 3;
                        if (side >= 0 && side <= 1 && gest >= 0 && gest <= 2) {
                            state.touch[side][gest] = fn;
                        }
                    }
                }
                notifyState();
                break;
            }
            case Protocol.CMD_KEY_SET_RSP: {
                if (p.length > 0 && p[0] == 0) {
                    io.execute(new Runnable() {
                        @Override public void run() { sendTl(Protocol.CMD_KEY_REQ, Protocol.KEY_REQ_PAYLOAD); }
                    });
                }
                break;
            }
            case Protocol.CMD_MULTI_RSP: {
                List<Protocol.MultiDevice> list = Protocol.parseMultiConnect(p, true);
                if (list != null) {
                    synchronized (state) { state.multiDevices = list; }
                    notifyState();
                }
                break;
            }
            case Protocol.CMD_USE_TIME_RSP: {
                if (p.length < 2 || p[0] != 0) break;
                int count = p[1] & 0xFF;
                if (p.length < 2 + count * 5) break;
                synchronized (state) {
                    for (int i = 0, off = 2; i < count; i++, off += 5) {
                        int role = p[off] & 0xFF;
                        int min = (p[off + 1] & 0xFF)
                            | ((p[off + 2] & 0xFF) << 8)
                            | ((p[off + 3] & 0xFF) << 16)
                            | ((p[off + 4] & 0xFF) << 24);
                        if (role == 1) state.leftUseMin = min;
                        else if (role == 2) state.rightUseMin = min;
                    }
                }
                notifyState();
                break;
            }
            case Protocol.CMD_EVENT:
            case Protocol.CMD_NOTIFICATION: {
                if (p.length > 0 && p[0] == 0x03) {
                    int m = Protocol.parseAncEvent(p);
                    if (m >= 0) {
                        log("RX Event ANC: " + Protocol.ancName(m));
                        synchronized (state) { state.ancMode = m; }
                        notifyState();
                        break;
                    }
                } else if (p.length >= 2 && p[0] == 0x05) {
                    boolean gm = (p[1] & 0xFF) == 1;
                    log("RX Event GameMode: " + (gm ? "ON" : "OFF"));
                    synchronized (state) { state.gameMode = gm; }
                    notifyState();
                    break;
                } else if (p.length > 1 && p[0] == 0x06) {
                    List<Protocol.MultiDevice> list = Protocol.parseMultiConnect(Arrays.copyOfRange(p, 1, p.length), false);
                    if (list != null) {
                        synchronized (state) { state.multiDevices = list; }
                        notifyState();
                        break;
                    }
                }
                scheduleRefresh();
                break;
            }
        }
    }

    private void scheduleRefresh() {
        main.removeCallbacks(refreshTask);
        main.postDelayed(refreshTask, 120L);
    }

    private void notifyState() {
        final int cs = connState;
        final Protocol.State snap;
        synchronized (state) { snap = state.copy(); }
        main.post(new Runnable() {
            @Override public void run() { listener.onStateChanged(cs, snap); }
        });
    }

    private void log(final String msg) {
        main.post(new Runnable() {
            @Override public void run() { listener.onLog(msg); }
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void handleHwPacket(HuaweiProto.Pkt pkt) {
        switch (pkt.cmd) {
            case HuaweiProto.CMD_BAT_READ:
            case HuaweiProto.CMD_BAT_NOTIFY: {
                byte[] p1 = pkt.get(1), p2 = pkt.get(2), p3 = pkt.get(3);
                synchronized (state) {
                    if (p2 != null && p2.length >= 3) {
                        boolean lc = p3 != null && p3.length >= 3 && p3[0] == 1;
                        boolean rc = p3 != null && p3.length >= 3 && p3[1] == 1;
                        boolean cc = p3 != null && p3.length >= 3 && p3[2] == 1;
                        int l = p2[0] & 0xFF, r = p2[1] & 0xFF, c = p2[2] & 0xFF;
                        state.leftBat = (l >= 1 && l <= 100) || (l == 0 && lc) ? l : -1;
                        state.rightBat = (r >= 1 && r <= 100) || (r == 0 && rc) ? r : -1;
                        state.caseBat = (c >= 1 && c <= 100) || (c == 0 && cc) ? c : -1;
                        state.leftChg = lc; state.rightChg = rc; state.caseChg = cc;
                    } else if (p1 != null && p1.length >= 1) {
                        int g = p1[0] & 0xFF;
                        boolean chg = false;
                        if (p3 != null) for (byte b : p3) if (b == 1) chg = true;
                        state.leftBat = state.rightBat = g <= 100 ? g : -1;
                        state.caseBat = -1;
                        state.leftChg = state.rightChg = chg; state.caseChg = false;
                    }
                }
                notifyState();
                break;
            }
            case HuaweiProto.CMD_ANC_R: {
                byte[] d = pkt.get(1);
                if (d != null && d.length >= 1) {
                    int m = d[d.length - 1] & 0xFF;
                    int anc = m == 1 ? Protocol.ANC_ON : (m == 2 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
                    synchronized (state) {
                        state.ancMode = anc;
                        state.ancLevel = (!Protocol.isHonorChoice(activeModel) && d.length >= 2 && m == 1) ? (d[0] & 0xFF) : -1;
                    }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_ANC_EV: {
                io.execute(new Runnable() {
                    @Override public void run() { sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_ANC_R, 1, 2)); }
                });
                break;
            }
            case HuaweiProto.CMD_LOW_LAT: {
                byte[] v = pkt.get(2);
                if (v != null && v.length > 0) {
                    synchronized (state) { state.gameMode = (v[0] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_AUTOPAUSE_R: {
                byte[] v = pkt.get(1);
                if (v != null && v.length > 0) {
                    synchronized (state) { state.autoPlayPause = (v[0] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_SQP_R: {
                byte[] v = pkt.get(2);
                if (v != null && v.length > 0) {
                    synchronized (state) { state.hqAudio = (v[0] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_EQ_R: {
                byte[] v = pkt.get(2);
                if (v != null && v.length > 0) {
                    int id = v[0];
                    int eq = HuaweiProto.hwToEq(id, Protocol.hwEqPro3(activeModel));
                    synchronized (state) {
                        if (eq >= 0) {
                            state.eqId = eq; state.customEqName = null;
                        } else {
                            state.eqId = -1;
                            String cn = HuaweiProto.findCustomEqName(pkt.get(8), id);
                            state.customEqName = cn != null ? cn : ("EQ #" + id);
                        }
                    }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_DC_R: {
                byte[] v = pkt.get(1);
                if (v != null && v.length > 0) {
                    synchronized (state) { state.dualConn = (v[0] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_DC_ENUM: {
                byte[] macB = pkt.get(4), idxB = pkt.get(3), stB = pkt.get(5), nmB = pkt.get(9);
                if (macB != null && macB.length >= 6) {
                    StringBuilder mac = new StringBuilder();
                    for (int i = 0; i < 6; i++) {
                        if (i > 0) mac.append(':');
                        mac.append(String.format("%02X", macB[i] & 0xFF));
                    }
                    int idx = (idxB != null && idxB.length > 0) ? (idxB[0] & 0xFF) : hwMultiMap.size();
                    int st = (stB != null && stB.length > 0) ? (stB[0] & 0xFF) : 0;
                    String nm = (nmB != null && nmB.length > 0)
                        ? new String(nmB, StandardCharsets.UTF_8).replace("\0", "") : mac.toString();
                    synchronized (state) {
                        hwMultiMap.put(idx, new Protocol.MultiDevice(mac.toString(), nm, st));
                        state.multiDevices = new ArrayList<>(hwMultiMap.values());
                    }
                    notifyState();
                }
                break;
            }
            case HuaweiProto.CMD_DC_EV: {
                io.execute(new Runnable() {
                    @Override public void run() {
                        hwMultiMap.clear();
                        sendRaw(HuaweiProto.readReq(HuaweiProto.CMD_DC_ENUM, 1));
                    }
                });
                break;
            }
            case HuaweiProto.CMD_DTAP_R:
            case HuaweiProto.CMD_TTAP_R:
            case HuaweiProto.CMD_LTAP_R: {
                int gi = pkt.cmd == HuaweiProto.CMD_DTAP_R ? 0 : (pkt.cmd == HuaweiProto.CMD_TTAP_R ? 1 : 2);
                boolean fb3 = activeModel == Protocol.MODEL_HW_3;
                byte[] l = pkt.get(1), r = pkt.get(2);
                synchronized (state) {
                    if (l != null && l.length > 0) state.touch[0][gi] = HuaweiProto.hwTapToFn(l[0], gi, fb3);
                    if (r != null && r.length > 0) state.touch[1][gi] = HuaweiProto.hwTapToFn(r[0], gi, fb3);
                }
                notifyState();
                break;
            }
        }
    }

    private void handleNothingPacket(NothingProto.Pkt pkt) {
        byte[] p = pkt.payload;
        switch (pkt.cmd) {
            case NothingProto.CMD_BAT_RSP:
            case NothingProto.CMD_BAT_EV: {
                if (p.length < 1) break;
                int count = p[0] & 0xFF;
                synchronized (state) {
                    for (int i = 0, off = 1; i < count && off + 1 < p.length; i++, off += 2) {
                        int part = p[off] & 0xFF;
                        int val = p[off + 1] & 0xFF;
                        int lvl = val & 0x7F;
                        boolean chg = (val & 0x80) != 0;
                        int eff = lvl <= 100 ? lvl : -1;
                        if (part == 2) { state.leftBat = eff; state.leftChg = chg; }
                        else if (part == 3) { state.rightBat = eff; state.rightChg = chg; }
                        else if (part == 4) { state.caseBat = (eff == 0 && !chg) ? -1 : eff; state.caseChg = chg; }
                        else if (part == 6) { state.leftBat = state.rightBat = eff; state.leftChg = state.rightChg = chg; }
                    }
                }
                notifyState();
                break;
            }
            case NothingProto.CMD_ANC_RSP:
            case NothingProto.CMD_ANC_EV: {
                for (int off = 0; off + 1 < p.length; off += 3) {
                    if ((p[off] & 0xFF) == 1) {
                        int v = p[off + 1] & 0xFF;
                        synchronized (state) {
                            if (v == 5) { state.ancMode = Protocol.ANC_OFF; }
                            else if (v == 7) { state.ancMode = Protocol.ANC_TRANSPARENCY; }
                            else if (v >= 1 && v <= 4) {
                                state.ancMode = Protocol.ANC_ON;
                                state.ancLevel = v == 3 ? 1 : (v == 2 ? 0 : (v == 4 ? 3 : 2));
                            }
                        }
                        notifyState();
                        break;
                    }
                }
                break;
            }
            case NothingProto.CMD_EQ_C_RSP:
            case NothingProto.CMD_EQ_L_RSP: {
                if (p.length < 1) break;
                int id = p[0] & 0xFF;
                boolean listening = pkt.cmd == NothingProto.CMD_EQ_L_RSP;
                synchronized (state) {
                    state.eqId = -1; state.customEqName = null;
                    if (listening) {
                        if (id == 3) state.eqId = 0;
                        else if (id == 4) state.eqId = 2;
                        else if (id == 2) state.eqId = 3;
                        else if (id == 1) state.eqId = 1;
                        else state.customEqName = id == 7 ? "Dirac Opteo" : (id == 5 ? "Classical" : "Custom");
                    } else {
                        if (id == 0) state.eqId = 0;
                        else if (id == 1) state.eqId = 2;
                        else if (id == 2) state.eqId = 3;
                        else if (id == 3) state.eqId = 1;
                        else state.customEqName = "Custom";
                    }
                }
                notifyState();
                break;
            }
            case NothingProto.CMD_INEAR_RSP: {
                if (p.length >= 3) {
                    synchronized (state) { state.autoPlayPause = (p[2] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
            case NothingProto.CMD_LAT_RSP: {
                if (p.length >= 1) {
                    synchronized (state) { state.gameMode = (p[0] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
            case NothingProto.CMD_BASS_RSP: {
                if (p.length >= 1) {
                    if (p.length >= 2) {
                        int lvl = p[1] & 0xFF;
                        if (lvl >= 1 && lvl <= 5) nothingBassLvl = lvl;
                    }
                    synchronized (state) { state.dynamicAudio = (p[0] & 0xFF) == 1; }
                    notifyState();
                }
                break;
            }
        }
    }

    private void handleGalaxyPacket(GalaxyProto.Pkt pkt) {
        byte[] p = pkt.payload;
        switch (pkt.msgId) {
            case GalaxyProto.MSG_EXT_STATUS: {
                if (p.length >= 8) {
                    int rev = p[0] & 0xFF;
                    int bl = p[2] & 0xFF, br = p[3] & 0xFF, pl = p[6] & 0xFF, bc = p[7] & 0xFF;
                    int lp = (pl >>> 4) & 0x0F, rp = pl & 0x0F;
                    boolean lc = false, rc = false, cc = false;
                    if (p.length > 36 && rev >= 10) {
                        int ch = p[36] & 0xFF;
                        lc = (ch & 0x10) != 0; rc = (ch & 0x04) != 0; cc = (ch & 0x01) != 0;
                    }
                    synchronized (state) {
                        state.leftBat = (bl >= 1 && bl <= 100) || (bl == 0 && lp != 0) ? bl : -1;
                        state.rightBat = (br >= 1 && br <= 100) || (br == 0 && rp != 0) ? br : -1;
                        state.caseBat = (bc >= 1 && bc <= 100) ? bc : -1;
                        state.leftChg = lc; state.rightChg = rc; state.caseChg = cc;
                        int eqIdx = activeModel == Protocol.MODEL_GALAXY_PLUS ? 11 : 9;
                        if (p.length > eqIdx) {
                            int eq = p[eqIdx] & 0xFF;
                            state.eqId = -1; state.customEqName = null;
                            if (eq == 0) state.eqId = 0;
                            else if (eq == 1) state.eqId = 1;
                            else if (eq == 3) state.eqId = 3;
                            else if (eq == 4) state.eqId = 2;
                            else if (eq == 2) state.customEqName = "Soft";
                            else if (eq == 5) state.customEqName = "Treble Boost";
                        }
                        if (p.length > 12) {
                            int nc = p[12] & 0xFF;
                            if (activeModel == Protocol.MODEL_GALAXY_LIVE) {
                                state.ancMode = nc == 1 ? Protocol.ANC_ON : Protocol.ANC_OFF;
                            } else if (activeModel == Protocol.MODEL_GALAXY_MODERN) {
                                state.ancMode = (nc == 1 || nc == 3) ? Protocol.ANC_ON : (nc == 2 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
                            }
                        }
                        if (p.length > 19) state.dualConn = (p[19] & 0xFF) == 0;
                    }
                    notifyState();
                }
                if (!galaxyMgrSent) {
                    galaxyMgrSent = true;
                    io.execute(new Runnable() {
                        @Override public void run() {
                            sendRaw(GalaxyProto.build(GalaxyProto.MSG_MGR_INFO, new byte[]{1, 2, 0x22}, activeModel == Protocol.MODEL_GALAXY_BUDS));
                        }
                    });
                }
                break;
            }
            case GalaxyProto.MSG_STATUS: {
                if (p.length >= 7) {
                    int bl = p[1] & 0xFF, br = p[2] & 0xFF, pl = p[5] & 0xFF, bc = p[6] & 0xFF;
                    int lp = (pl >>> 4) & 0x0F, rp = pl & 0x0F;
                    synchronized (state) {
                        state.leftBat = (bl >= 1 && bl <= 100) || (bl == 0 && lp != 0) ? bl : -1;
                        state.rightBat = (br >= 1 && br <= 100) || (br == 0 && rp != 0) ? br : -1;
                        state.caseBat = (bc >= 1 && bc <= 100) ? bc : -1;
                    }
                    notifyState();
                }
                break;
            }
            case GalaxyProto.MSG_NC_UPD: {
                if (p.length >= 1) {
                    int nc = p[0] & 0xFF;
                    synchronized (state) {
                        state.ancMode = (nc == 1 || nc == 3) ? Protocol.ANC_ON : (nc == 2 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
                    }
                    notifyState();
                }
                break;
            }
            case GalaxyProto.MSG_AMB_UPD: {
                if (p.length >= 1) {
                    synchronized (state) { state.ancMode = (p[0] & 0xFF) == 1 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF; }
                    notifyState();
                }
                break;
            }
            case GalaxyProto.MSG_NR_UPD: {
                if (p.length >= 1) {
                    synchronized (state) { state.ancMode = (p[0] & 0xFF) == 1 ? Protocol.ANC_ON : Protocol.ANC_OFF; }
                    notifyState();
                }
                break;
            }
        }
    }

    private void handleSonyPacket(ExtraProtos.SonyPkt pkt) {
        if (pkt.type != 0x01) {
            final int rxSeq = pkt.seq;
            io.execute(new Runnable() { @Override public void run() { sendRaw(ExtraProtos.sonyAck(rxSeq)); } });
        }
        if (pkt.type != 0x0C || pkt.payload.length < 2) return;
        byte[] p = pkt.payload;
        int cmd = p[0] & 0xFF, inq = p[1] & 0xFF;
        if (cmd == 0x23 || cmd == 0x25) {
            synchronized (state) {
                if (inq == 0x00 && p.length >= 4) {
                    int lvl = p[2] & 0xFF; boolean chg = (p[3] & 0xFF) == 1;
                    state.leftBat = state.rightBat = lvl <= 100 ? lvl : -1;
                    state.leftChg = state.rightChg = chg;
                } else if (inq == 0x01 && p.length >= 6) {
                    int l = p[2] & 0xFF, r = p[4] & 0xFF;
                    state.leftBat = l <= 100 ? l : -1; state.leftChg = (p[3] & 0xFF) == 1;
                    state.rightBat = r <= 100 ? r : -1; state.rightChg = (p[5] & 0xFF) == 1;
                } else if (inq == 0x02 && p.length >= 4) {
                    int c = p[2] & 0xFF; boolean chg = (p[3] & 0xFF) == 1;
                    state.caseBat = (c <= 100 && !(c == 0 && !chg)) ? c : -1; state.caseChg = chg;
                }
            }
            notifyState();
        } else if ((cmd == 0x67 || cmd == 0x69) && p.length >= 5) {
            int total = p[3] & 0xFF, mode = p[4] & 0xFF;
            synchronized (state) {
                state.ancMode = total == 0 ? Protocol.ANC_OFF : (mode == 0 ? Protocol.ANC_ON : Protocol.ANC_TRANSPARENCY);
            }
            notifyState();
        } else if ((cmd == 0x57 || cmd == 0x59) && p.length >= 3) {
            int id = p[2] & 0xFF;
            synchronized (state) {
                state.eqId = -1; state.customEqName = null;
                if (id == 0) state.eqId = 0;
                else if (id == 4) state.eqId = 2;
                else if (id == 1 || id == 2) state.eqId = 3;
                else if (id == 5) state.eqId = 1;
                else state.customEqName = "Sony EQ #" + id;
            }
            notifyState();
        }
    }

    private void handleBosePacket(ExtraProtos.BosePkt pkt) {
        byte[] p = pkt.payload;
        if (pkt.block == 0x02 && pkt.func == 0x02 && p.length >= 1) {
            int lvl = p[0] & 0xFF;
            synchronized (state) {
                state.leftBat = state.rightBat = lvl <= 100 ? lvl : -1;
            }
            notifyState();
        } else if (pkt.block == 0x01 && pkt.func == 0x06 && p.length >= 1) {
            int v = p[0] & 0xFF;
            synchronized (state) {
                state.ancMode = v == 0 ? Protocol.ANC_OFF : (v == 3 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_ON);
            }
            notifyState();
        } else if (pkt.block == 0x01 && pkt.func == 0x07 && p.length >= 1) {
            synchronized (state) { state.dualConn = (p[0] & 0xFF) != 0; }
            notifyState();
        }
    }

    private void handleSoundcorePacket(ExtraProtos.ScPkt pkt) {
        byte[] p = pkt.payload;
        if (pkt.group == 0x01 && pkt.cmd == 0x03 && p.length >= 2) {
            synchronized (state) {
                if (p.length >= 4) {
                    int l = p[0] & 0xFF, r = p[1] & 0xFF;
                    state.leftBat = l <= 100 ? l : -1; state.rightBat = r <= 100 ? r : -1;
                    state.leftChg = (p[2] & 0xFF) == 1; state.rightChg = (p[3] & 0xFF) == 1;
                } else {
                    int b = p[0] & 0xFF;
                    state.leftBat = state.rightBat = b <= 100 ? b : -1;
                    state.leftChg = state.rightChg = (p[1] & 0xFF) == 1;
                }
            }
            notifyState();
        } else if (pkt.group == 0x06 && (pkt.cmd == 0x01 || pkt.cmd == 0x81) && p.length >= 1) {
            int m = p[0] & 0xFF;
            synchronized (state) {
                state.ancMode = m == 0 ? Protocol.ANC_ON : (m == 1 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
            }
            notifyState();
        } else if (pkt.group == 0x01 && pkt.cmd == 0x11 && p.length >= 1) {
            synchronized (state) { state.gameMode = (p[0] & 0xFF) == 1; }
            notifyState();
        }
    }

    private void handleXiaomiPacket(ExtraProtos.XiaomiPkt pkt) {
        byte[] p = pkt.payload;
        if (pkt.opcode == 0x50) {
            if (pkt.isResp()) {
                io.execute(new Runnable() { @Override public void run() { sendRaw(ExtraProtos.xiaomiReq(0x51, new byte[]{0x01, 0x00})); } });
            } else if (p.length >= 17) {
                final byte[] ans = ExtraProtos.xiaomiChallengeResponse(Arrays.copyOfRange(p, 1, 17));
                final byte[] rep = new byte[17];
                rep[0] = 0x01;
                System.arraycopy(ans, 0, rep, 1, 16);
                final int seq = pkt.seq;
                io.execute(new Runnable() { @Override public void run() { sendRaw(ExtraProtos.xiaomiBuild(0x04, 0x50, seq, rep)); } });
            }
            return;
        }
        if (pkt.opcode == 0x51 && !pkt.isResp()) {
            final int seq = pkt.seq;
            io.execute(new Runnable() { @Override public void run() { sendRaw(ExtraProtos.xiaomiBuild(0x04, 0x51, seq, new byte[]{0x01})); } });
            if (!xiaomiAuthed) {
                xiaomiAuthed = true;
                requestFullState(false);
            }
            return;
        }
        if (pkt.opcode == 0x03) {
            for (int i = 0; i + 1 < p.length; ) {
                int l = p[i] & 0xFF;
                if (l < 1) break;
                int idx = p[i + 1] & 0xFF;
                if (idx == 0x07 && l >= 4 && i + 4 < p.length) {
                    applyXiaomiBats(p, i + 2);
                }
                i += l + 1;
            }
            return;
        }
        if (pkt.opcode == 0x07) {
            for (int i = 0; i + 2 < p.length; ) {
                int l = p[i] & 0xFF;
                if (l < 1) break;
                int idx = p[i + 1] & 0xFF, v = p[i + 2] & 0xFF;
                if (idx == 0x09 && l >= 2 && v <= 2) {
                    synchronized (state) {
                        state.ancMode = v == 1 ? Protocol.ANC_ON : (v == 2 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
                    }
                    notifyState();
                } else if (idx == 0x0A && l >= 2) {
                    synchronized (state) { state.autoPlayPause = (v == 0); }
                    notifyState();
                }
                i += l + 1;
            }
            return;
        }
        if (pkt.opcode == 0x02 && !pkt.isResp()) {
            for (int i = 0; i + 1 < p.length; ) {
                int l = p[i] & 0xFF;
                if (l < 1) break;
                int idx = p[i + 1] & 0xFF;
                if (idx == 0x00 && l >= 4 && i + 4 < p.length) applyXiaomiBats(p, i + 2);
                else if (idx == 0x04 && l >= 2 && i + 2 < p.length) {
                    int v = p[i + 2] & 0xFF;
                    if (v <= 2) {
                        synchronized (state) {
                            state.ancMode = v == 1 ? Protocol.ANC_ON : (v == 2 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
                        }
                        notifyState();
                    }
                }
                i += l + 1;
            }
            final int seq = pkt.seq;
            io.execute(new Runnable() { @Override public void run() { sendRaw(ExtraProtos.xiaomiBuild(0x04, 0x02, seq, null)); } });
            return;
        }
        if (pkt.opcode == 0xF3 || pkt.opcode == 0xF4) {
            int pos = 0;
            while (pos + 3 <= p.length) {
                int l = p[pos] & 0xFF;
                int key = ((p[pos + 1] & 0xFF) << 8) | (p[pos + 2] & 0xFF);
                int dataStart, end;
                if (l == 0xFF) {
                    if (pos + 5 > p.length) break;
                    int extLen = ((p[pos + 3] & 0xFF) << 8) | (p[pos + 4] & 0xFF);
                    dataStart = pos + 5;
                    end = dataStart + extLen;
                } else {
                    if (l < 2) break;
                    dataStart = pos + 3;
                    end = pos + 1 + l;
                }
                if (end > p.length) break;
                if (end > dataStart) {
                    int v = p[dataStart] & 0xFF;
                    synchronized (state) {
                        if (key == 0x03) state.autoAnswer = v != 0;
                        else if (key == 0x04) state.dualConn = v != 0;
                        else if (key == 0x2F) state.gameMode = v != 0;
                        else if (key == 0x07) {
                            state.eqId = (v == 0 || v == 0x15) ? 0 : (v == 1 ? 2 : (v == 6 ? 3 : (v == 5 ? 1 : -1)));
                            state.customEqName = state.eqId < 0 ? (v == 0xFF ? "Custom" : ("EQ #" + v)) : null;
                        } else if (key == 0x0B && end - dataStart >= 2 && v == 1) {
                            int lv = p[dataStart + 1] & 0xFF;
                            state.ancLevel = lv == 1 ? 1 : (lv == 0 ? 0 : (lv == 2 ? 2 : 3));
                        }
                    }
                }
                pos = end;
            }
            notifyState();
            if (pkt.opcode == 0xF4 && !pkt.isResp()) {
                final int seq = pkt.seq;
                io.execute(new Runnable() { @Override public void run() { sendRaw(ExtraProtos.xiaomiBuild(0x04, 0xF4, seq, null)); } });
            }
        }
    }

    private void applyXiaomiBats(byte[] p, int off) {
        int l = p[off] & 0x7F; boolean lc = (p[off] & 0x80) != 0;
        int r = p[off + 1] & 0x7F; boolean rc = (p[off + 1] & 0x80) != 0;
        int c = p[off + 2] & 0x7F; boolean cc = (p[off + 2] & 0x80) != 0;
        synchronized (state) {
            state.leftBat = l <= 100 ? l : -1; state.leftChg = lc;
            state.rightBat = r <= 100 ? r : -1; state.rightChg = rc;
            state.caseBat = (c <= 100 && !(c == 0 && !cc)) ? c : -1; state.caseChg = cc;
        }
        notifyState();
    }

    private void handleSennheiserPacket(ExtraProtos.GaiaPkt pkt) {
        if (pkt.vendor != 0x0495) return;
        byte[] p = pkt.payload;
        if (pkt.feature == 0x06 && pkt.command == 0x02 && p.length >= 1) {
            synchronized (state) {
                if (p.length >= 3) {
                    int l = p[0] & 0xFF, r = p[1] & 0xFF, c = p[2] & 0xFF;
                    state.leftBat = l <= 100 ? l : -1; state.rightBat = r <= 100 ? r : -1; state.caseBat = c <= 100 ? c : -1;
                } else {
                    int b = p[0] & 0xFF; state.leftBat = state.rightBat = b <= 100 ? b : -1;
                }
            }
            notifyState();
        } else if (pkt.feature == 0x05 && (pkt.command == 0x01 || pkt.command == 0x02 || pkt.command == 0x04) && p.length >= 1) {
            synchronized (state) {
                if (pkt.command == 0x02) { if ((p[0] & 0xFF) == 0) state.ancMode = Protocol.ANC_OFF; }
                else if (pkt.command == 0x04) state.ancMode = (p[0] & 0xFF) == 1 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_ON;
                else state.ancMode = (p[0] & 0xFF) == 0 ? Protocol.ANC_OFF : (p.length >= 2 && (p[1] & 0xFF) == 1 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_ON);
            }
            notifyState();
        } else if (pkt.feature == 0x06 && pkt.command == 0x06 && p.length >= 1) {
            synchronized (state) { state.spatialAudio = (p[0] & 0xFF) == 1; }
            notifyState();
        }
    }

    private void handleJabraPacket(ExtraProtos.JabraPkt pkt) {
        byte[] p = pkt.payload;
        if (pkt.feat == 0x0101 && p.length >= 1) {
            synchronized (state) {
                if (p.length >= 3) {
                    int l = p[0] & 0xFF, r = p[1] & 0xFF, c = p[2] & 0xFF;
                    state.leftBat = l <= 100 ? l : -1; state.rightBat = r <= 100 ? r : -1; state.caseBat = c <= 100 ? c : -1;
                } else {
                    int b = p[0] & 0xFF; state.leftBat = state.rightBat = b <= 100 ? b : -1;
                }
            }
            notifyState();
        } else if (pkt.feat == 0x0104 && p.length >= 1) {
            int v = p[0] & 0xFF;
            synchronized (state) { state.ancMode = v == 2 ? Protocol.ANC_ON : (v == 1 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF); }
            notifyState();
        } else if (pkt.feat == 0x0107 && p.length >= 1) {
            synchronized (state) { state.dualConn = (p[0] & 0xFF) == 1; }
            notifyState();
        }
    }

    private void handlePixelBudsPacket(ExtraProtos.PbPkt pkt) {
        if (pkt.ch > 0) pbChannel = pkt.ch;
        List<ExtraProtos.PbField> fs = ExtraProtos.pbReadFields(pkt.payload);
        if (pkt.method == ExtraProtos.PB_M_SUB_BAT) {
            synchronized (state) {
                for (ExtraProtos.PbField f : fs) {
                    if (f.wire == 2 && f.b != null && (f.num == 1 || f.num == 2 || f.num == 3)) {
                        int lvl = -1; boolean chg = false;
                        for (ExtraProtos.PbField sub : ExtraProtos.pbReadFields(f.b)) {
                            if (sub.num == 1) lvl = (int) sub.v;
                            else if (sub.num == 2) chg = sub.v != 0;
                        }
                        if (lvl >= 0 && lvl <= 100) {
                            if (f.num == 1) { state.leftBat = lvl; state.leftChg = chg; }
                            else if (f.num == 2) { state.rightBat = lvl; state.rightChg = chg; }
                            else { state.caseBat = (lvl == 0 && !chg) ? -1 : lvl; state.caseChg = chg; }
                        }
                    }
                }
            }
            notifyState();
            return;
        }
        for (ExtraProtos.PbField f : fs) {
            if (f.num == 4 && f.wire == 2 && f.b != null) {
                synchronized (state) {
                    for (ExtraProtos.PbField sf : ExtraProtos.pbReadFields(f.b)) {
                        if (sf.num == 11) {
                            state.ancMode = sf.v == 2 ? Protocol.ANC_ON : (sf.v == 3 ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
                        } else if (sf.num == 5) {
                            state.autoPlayPause = sf.v != 0;
                        } else if (sf.num == 21) {
                            state.dualConn = sf.v != 0;
                        }
                    }
                }
                notifyState();
            }
        }
    }

    private void handleJblPacket(ExtraProtos.JblPkt pkt) {
        byte[] p = pkt.payload;
        if (pkt.cmd == 0x25 && p.length >= 2) {
            synchronized (state) {
                for (int i = 0; i + 1 < p.length; i += 2) {
                    int id = p[i] & 0xFF, v = p[i + 1] & 0xFF;
                    int lvl = v & 0x7F; boolean chg = (v & 0x80) != 0;
                    if (id == 1) { state.leftBat = lvl <= 100 ? lvl : -1; state.leftChg = chg; }
                    else if (id == 2) { state.rightBat = lvl <= 100 ? lvl : -1; state.rightChg = chg; }
                    else if (id == 3) { state.caseBat = (lvl <= 100 && !(lvl == 0 && !chg)) ? lvl : -1; state.caseChg = chg; }
                }
            }
            notifyState();
        } else if (pkt.cmd == 0x91 && p.length >= 5) {
            boolean anc = false, amb = false;
            for (int i = 1; i + 1 < p.length; i += 2) {
                int k = p[i] & 0xFF, v = p[i + 1] & 0xFF;
                if (k == 1) anc = v != 0;
                else if (k == 2) amb = v != 0;
            }
            synchronized (state) {
                state.ancMode = anc ? Protocol.ANC_ON : (amb ? Protocol.ANC_TRANSPARENCY : Protocol.ANC_OFF);
            }
            notifyState();
        } else if (pkt.cmd == 0x22 && p.length >= 2) {
            int f = p[0] & 0xFF, v = p[1] & 0xFF;
            synchronized (state) {
                if (f == 0x35) state.autoPlayPause = v == 1;
                else if (f == 0x40) {
                    state.eqId = v == 0 ? 0 : (v == 2 ? 2 : (v == 7 ? 3 : (v == 3 ? 1 : -1)));
                    state.customEqName = state.eqId < 0 ? ("JBL EQ #" + v) : null;
                } else if (f == 0x9D) state.spatialAudio = (p.length >= 3 ? (p[2] & 0xFF) : v) == 1;
            }
            notifyState();
        }
    }
}
