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
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae4"),
        UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3")
    };
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    interface Listener {
        void onStateChanged(int connState, Protocol.State state);
        void onLog(String line);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object writeLock = new Object();

    private volatile int connState = CONN_DISCONNECTED;
    private volatile int activeModel = Protocol.MODEL_UNKNOWN;
    private volatile int sessionGen = 0;

    private BluetoothSocket socket;
    private InputStream inStream;
    private OutputStream outStream;
    private Thread readerThread;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean useGatt;

    private final Protocol.State state = new Protocol.State();
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

    void connect(final BluetoothDevice device) {
        disconnect();
        final int gen = ++sessionGen;
        activeModel = Protocol.detectModel(device.getName());
        connState = CONN_CONNECTING;
        notifyState();
        log("Connecting to " + device.getName() + " (" + device.getAddress() + ")...");

        io.execute(new Runnable() {
            @Override public void run() {
                if (gen != sessionGen) return;
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null) {
                    try { bm.getAdapter().cancelDiscovery(); } catch (Exception ignored) {}
                }
                for (UUID uuid : SPP_UUIDS) {
                    if (gen != sessionGen) return;
                    if (trySpp(device, uuid, false) || trySpp(device, uuid, true)) {
                        if (gen != sessionGen) { disconnect(); return; }
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
                log("SPP unavailable, falling back to GATT...");
                connectGatt(device);
            }
        });
    }

    private boolean trySpp(BluetoothDevice dev, UUID uuid, boolean insecure) {
        BluetoothSocket s = null;
        try {
            s = insecure
                ? dev.createInsecureRfcommSocketToServiceRecord(uuid)
                : dev.createRfcommSocketToServiceRecord(uuid);
            s.connect();
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
                        List<Protocol.Frame> frames = new ArrayList<>();
                        acc = Protocol.parseStream(merged, frames);
                        for (Protocol.Frame f : frames) handleFrame(f);
                    }
                } catch (IOException e) {
                    if (gen == sessionGen) log("SPP closed: " + e.getMessage());
                } finally {
                    if (gen == sessionGen && connState == CONN_CONNECTED) {
                        main.post(new Runnable() { @Override public void run() { disconnect(); } });
                    }
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void connectGatt(BluetoothDevice dev) {
        useGatt = true;
        gatt = dev.connectGatt(ctx, false, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("GATT connected, discovering services...");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("GATT disconnected");
                    main.post(new Runnable() { @Override public void run() { disconnect(); } });
                }
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) { disconnect(); return; }
                BluetoothGattCharacteristic w = null, n = null;
                for (BluetoothGattService svc : g.getServices()) {
                    String u = svc.getUuid().toString().toLowerCase();
                    if (u.startsWith("00001800") || u.startsWith("00001801")) continue;
                    BluetoothGattCharacteristic cw = null, cn = null;
                    for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                        int p = c.getProperties();
                        if (cw == null && ((p & 8) != 0 || (p & 4) != 0)) cw = c;
                        if (cn == null && ((p & 16) != 0 || (p & 32) != 0)) cn = c;
                    }
                    if (cw != null && cn != null) { w = cw; n = cn; break; }
                }
                if (w == null || n == null) { disconnect(); return; }
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
                    onReady(sessionGen);
                }
            }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int s) {
                connState = CONN_CONNECTED;
                notifyState();
                onReady(sessionGen);
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                Protocol.Frame f = Protocol.parseSingleFrame(value);
                if (f != null) handleFrame(f);
            }
            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                byte[] v = c.getValue();
                if (v != null) {
                    Protocol.Frame f = Protocol.parseSingleFrame(v);
                    if (f != null) handleFrame(f);
                }
            }
        }, BluetoothDevice.TRANSPORT_AUTO);
    }

    void disconnect() {
        sessionGen++;
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
        activeModel = Protocol.MODEL_UNKNOWN;
        connState = CONN_DISCONNECTED;
        resetState();
        notifyState();
    }

    private void resetState() {
        synchronized (state) {
            state.leftBat = state.rightBat = state.caseBat = -1;
            state.leftChg = state.rightChg = state.caseChg = false;
            state.ancMode = -1;
            state.gameMode = state.spatialAudio = state.hqAudio = state.dynamicAudio = null;
            state.dualConn = state.autoPlayPause = state.autoAnswer = null;
            state.eqId = -1;
            state.customEqName = null;
            state.leftUseMin = state.rightUseMin = -1;
            for (int i = 0; i < 2; i++) Arrays.fill(state.touch[i], -1);
            state.multiDevices.clear();
        }
    }

    private void onReady(final int gen) {
        io.execute(new Runnable() {
            @Override public void run() {
                sleep(150);
                if (gen != sessionGen) return;
                log("Device ready, requesting state...");
                requestFullState(true);
                byte[] evs = Protocol.supportsDual(activeModel)
                    ? new byte[]{0x03, 0x05, 0x01, 0x02, 0x06}
                    : new byte[]{0x03, 0x05, 0x01, 0x02};
                for (byte e : evs) {
                    sendTl(Protocol.CMD_NOTIFY_REG, new byte[]{e});
                    sleep(50);
                }
                main.postDelayed(pollTask, 2000L);
            }
        });
    }

    void requestFullState(final boolean includeUseTime) {
        io.execute(new Runnable() {
            @Override public void run() {
                if (connState != CONN_CONNECTED) return;
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
                if (connState != CONN_CONNECTED) return;
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
                log("TX ANC [" + Protocol.ancName(mode & 0xFF) + "]");
                sendTl(Protocol.CMD_ANC_SET, new byte[]{0x01, 0x01, mode});
            }
        });
    }

    void setEq(final byte eqId) {
        synchronized (state) { state.eqId = eqId & 0xFF; state.customEqName = null; }
        notifyState();
        io.execute(new Runnable() {
            @Override public void run() {
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
                log(String.format("TX Switch 0x%02X -> %s", featId & 0xFF, on ? "ON" : "OFF"));
                sendTl(Protocol.CMD_SWITCH_SET, new byte[]{featId, (byte) (on ? 1 : 0)});
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
                log(String.format("TX Key [side=%d act=%d -> 0x%02X]", devType, actionId, funcId & 0xFF));
                sendTl(Protocol.CMD_KEY_SET, new byte[]{0x01, devType, 0x01, actionId, funcId});
            }
        });
    }

    private void sendTl(int cmd, byte[] payload) {
        byte[] tl = Protocol.buildFrame(cmd, payload);
        byte[] wire = Protocol.wrapOppo(tl);
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
}
