package uwu.realness.gitveu.tiny;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.*;

import java.util.*;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements BudsLink.Listener {
    private static final int C_BG = 0xFF121212;
    private static final int C_SURFACE = 0xFF1E1E1E;
    private static final int C_SURFACE2 = 0xFF2A2A2A;
    private static final int C_ACCENT = 0xFFFFCC00;
    private static final int C_GREEN = 0xFF4CAF50;
    private static final int C_ORANGE = 0xFFFF9800;
    private static final int C_RED = 0xFFE53935;
    private static final int C_MUTED = 0xFF999999;

    private static final int ST_NO_PERM = 0;
    private static final int ST_NO_PAIRED = 1;
    private static final int ST_UNSUPPORTED = 2;
    private static final int ST_DISCONNECTED = 3;
    private static final int ST_CONNECTED_OS = 4;
    private static final int ST_ACTIVE = 5;

    private boolean ru;
    private float density;
    private BudsLink link;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;

    private static final String PREFS = "realness_tiny_prefs";
    private static final String K_MANUAL = "manual_model";
    private static final String K_LAST_MAC = "last_mac";
    private static final String K_LAST_NAME = "last_name";
    private static final String K_LAST_MODEL = "last_model";
    private static final String K_ALIAS_PREFIX = "alias_";
    private static final String K_LAST_CASE_LVL = "last_case_lvl_";
    private static final String K_LAST_CASE_TS = "last_case_ts_";
    private static final int REQ_CDM_RENAME = 409;
    private String pendingCdmRename = null;

    private SharedPreferences prefs;
    private FindTone findTone;
    private boolean hwFindActive = false;
    private String customAlias = null;
    private int[] customEqGains = new int[10];
    private int sysStatus = ST_NO_PAIRED;
    private int connState = BudsLink.CONN_DISCONNECTED;
    private int autoDetectedModel = Protocol.MODEL_UNKNOWN;
    private int manualModelOverride = Protocol.MODEL_UNKNOWN;
    private int detectedModel = Protocol.MODEL_UNKNOWN;
    private BluetoothDevice detectedDevice;
    private String lastDeviceName, lastDeviceMac, activeConnMac;
    private int activeConnModel = Protocol.MODEL_UNKNOWN;
    private String unsupportedName;
    private Protocol.State budsState = new Protocol.State();
    private final LinkedList<String> logs = new LinkedList<>();
    private int selectedSide = 0; // 0=L, 1=R
    private boolean logsExpanded = false;
    private boolean updatingUi = false;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int reconnectAttempt = 0;
    private final Runnable reconnectTask = new Runnable() {
        @Override public void run() {
            if (connState != BudsLink.CONN_DISCONNECTED) return;
            if (!hasPerms()) return;
            try {
                List<BluetoothDevice> a2dpDevs = a2dp != null ? a2dp.getConnectedDevices() : Collections.<BluetoothDevice>emptyList();
                if (detectedDevice != null && detectedModel != Protocol.MODEL_UNKNOWN) {
                    boolean stillOnline = false;
                    for (BluetoothDevice d : a2dpDevs) {
                        if (d.getAddress() != null && d.getAddress().equals(detectedDevice.getAddress())) {
                            stillOnline = true; break;
                        }
                    }
                    if (stillOnline) {
                        onLog("Auto-reconnecting to " + detectedDevice.getName() + " (attempt " + reconnectAttempt + ")...");
                        connectDevice(detectedDevice, detectedModel);
                        return;
                    }
                }
            } catch (Exception ignored) {}
            checkAndAutoConnect();
        }
    };

    // UI references
    private TextView tvTopTitle, btnRefresh;
    private View cardPerm, cardUnsupported;
    private TextView tvUnsupportedMsg;
    private TextView tvDevName, tvDevSub, tvBadge, tvHint, btnReconnect, btnFind, btnRename;
    private TextView tvBatL, tvBatC, tvBatR;
    private View barBatL, barBatC, barBatR;
    private View secAnc, cardAnc, rowAncLvl;
    private final TextView[] btnAnc = new TextView[3];
    private final TextView[] btnAncLvl = new TextView[4];
    private View secEq, cardEq;
    private final TextView[] btnEq = new TextView[4];
    private TextView tvCustomEq, btnCustomEq;
    private View secTouch, cardTouch;
    private final View[] rowGest = new View[3];
    private final TextView[] btnSide = new TextView[2];
    private final TextView[] tvGestVal = new TextView[3];
    private View secSpatial, cardSpatial;
    private final TextView[] btnSpatial = new TextView[2];

    private View cardHq, cardDynamic, rowBassLvl, secOther, cardGame, cardDual, cardAutoPlay, cardAutoAns, cardUseTime;
    private final TextView[] btnBassLvl = new TextView[5];
    private TextView tvHqDesc;
    private Switch swHq, swDynamic, swGame, swDual, swAutoPlay, swAutoAns;
    private LinearLayout multiListBox;
    private TextView tvUseL, tvUseR;
    private TextView tvLogsHeader, tvLogsBody, btnLogToggle;
    private ScrollView logsScroll;

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String a = intent.getAction();
            if (a == null) return;
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    int autoM = Protocol.detectModel(d.getName());
                    int effM = manualModelOverride != Protocol.MODEL_UNKNOWN ? manualModelOverride : autoM;
                    if (effM != Protocol.MODEL_UNKNOWN) {
                        if (link.isConnectedOrConnectingTo(d.getAddress(), effM)) return;
                        saveLastDevice(d, effM);
                        detectedDevice = d;
                        autoDetectedModel = autoM;
                        detectedModel = effM;
                        unsupportedName = null;
                        sysStatus = ST_CONNECTED_OS;
                        connectDevice(d, effM);
                        render();
                        return;
                    }
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && d.getAddress() != null && d.getAddress().equals(activeConnMac)) {
                    uiHandler.removeCallbacks(reconnectTask);
                    reconnectAttempt = 0;
                    link.disconnect();
                    activeConnMac = null;
                }
            }
            checkAndAutoConnect();
        }
    };

    private final BluetoothProfile.ServiceListener a2dpListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = (BluetoothA2dp) proxy;
                checkAndAutoConnect();
            }
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) a2dp = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ru = "ru".equalsIgnoreCase(Locale.getDefault().getLanguage());
        density = getResources().getDisplayMetrics().density;

        Window w = getWindow();
        w.setStatusBarColor(C_SURFACE);
        w.setNavigationBarColor(C_BG);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        manualModelOverride = prefs.getInt(K_MANUAL, Protocol.MODEL_UNKNOWN);
        lastDeviceMac = prefs.getString(K_LAST_MAC, null);
        lastDeviceName = prefs.getString(K_LAST_NAME, null);
        int savedM = prefs.getInt(K_LAST_MODEL, Protocol.MODEL_UNKNOWN);
        detectedModel = manualModelOverride != Protocol.MODEL_UNKNOWN ? manualModelOverride : savedM;
        customAlias = lastDeviceMac != null ? prefs.getString(K_ALIAS_PREFIX + lastDeviceMac, null) : null;
        findTone = new FindTone(this);
        link = new BudsLink(this, this);

        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, f, RECEIVER_EXPORTED);
        } else {
            registerReceiver(btReceiver, f);
        }
        try {
            if (adapter != null) adapter.getProfileProxy(this, a2dpListener, BluetoothProfile.A2DP);
        } catch (Exception ignored) {}

        checkPermsAndStart(true);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPerms()) {
            checkAndAutoConnect();
            if (connState == BudsLink.CONN_CONNECTED) link.requestFullState(true);
        }
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(reconnectTask);
        if (findTone != null) findTone.stop();
        if (hwFindActive) link.setHardwareFindBuds(false, FindTone.CH_BOTH);
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        try { if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp); } catch (Exception ignored) {}
        link.disconnect();
    }

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        for (String p : neededPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void checkPermsAndStart(boolean prompt) {
        if (hasPerms()) {
            checkAndAutoConnect();
        } else {
            sysStatus = ST_NO_PERM;
            if (prompt) requestPermissions(neededPerms(), 101);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasPerms()) checkAndAutoConnect();
        else sysStatus = ST_NO_PERM;
        render();
    }

    private void saveLastDevice(BluetoothDevice d, int model) {
        String nm = d.getName() != null ? d.getName() : Protocol.modelTitle(model);
        String mac = d.getAddress();
        lastDeviceName = nm;
        lastDeviceMac = mac;
        customAlias = mac != null ? prefs.getString(K_ALIAS_PREFIX + mac, null) : null;
        prefs.edit()
            .putString(K_LAST_MAC, mac)
            .putString(K_LAST_NAME, nm)
            .putInt(K_LAST_MODEL, model)
            .apply();
    }

    private void connectDevice(BluetoothDevice d, int model) {
        uiHandler.removeCallbacks(reconnectTask);
        if (link.isConnectedOrConnectingTo(d.getAddress(), model)) return;
        activeConnMac = d.getAddress();
        activeConnModel = model;
        link.connect(d, model);
    }

    private void showModelPicker() {
        String[] labels = new String[Protocol.PICKER_MODELS.length];
        int checked = 0;
        for (int i = 0; i < Protocol.PICKER_MODELS.length; i++) {
            int m = Protocol.PICKER_MODELS[i];
            if (m == manualModelOverride) checked = i;
            if (m == Protocol.MODEL_UNKNOWN) {
                String det = autoDetectedModel != Protocol.MODEL_UNKNOWN
                    ? Protocol.modelTitle(autoDetectedModel) : t("Not connected", "Не подключено");
                labels[i] = t("Automatic", "Автоматически") + " (" + det + ")";
            } else {
                labels[i] = Protocol.modelTitle(m);
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Model", "Модель"))
            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    manualModelOverride = Protocol.PICKER_MODELS[which];
                    prefs.edit().putInt(K_MANUAL, manualModelOverride).apply();
                    dialog.dismiss();
                    link.disconnect();
                    activeConnMac = null;
                    checkAndAutoConnect();
                }
            })
            .setNegativeButton(t("Cancel", "Отмена"), null)
            .show();
    }

    private void checkAndAutoConnect() {
        if (!hasPerms()) { sysStatus = ST_NO_PERM; render(); return; }
        try {
            List<BluetoothDevice> a2dpDevs = a2dp != null ? a2dp.getConnectedDevices() : Collections.<BluetoothDevice>emptyList();
            Set<BluetoothDevice> bonded = adapter != null ? adapter.getBondedDevices() : Collections.<BluetoothDevice>emptySet();

            BluetoothDevice target = null;
            for (BluetoothDevice d : a2dpDevs) {
                if (Protocol.detectModel(d.getName()) != Protocol.MODEL_UNKNOWN) {
                    target = d;
                    break;
                }
            }
            if (target == null && manualModelOverride != Protocol.MODEL_UNKNOWN && !a2dpDevs.isEmpty()) {
                target = a2dpDevs.get(0);
            }
            if (target != null) {
                int autoM = Protocol.detectModel(target.getName());
                int effM = manualModelOverride != Protocol.MODEL_UNKNOWN ? manualModelOverride : autoM;
                saveLastDevice(target, effM);
                detectedDevice = target;
                autoDetectedModel = autoM;
                detectedModel = effM;
                unsupportedName = null;
                boolean needReconn = connState == BudsLink.CONN_DISCONNECTED
                    || !target.getAddress().equals(activeConnMac)
                    || effM != activeConnModel;
                sysStatus = (!needReconn && connState == BudsLink.CONN_CONNECTED) ? ST_ACTIVE : ST_CONNECTED_OS;
                if (needReconn) connectDevice(target, effM);
                render();
                return;
            }
            if (connState != BudsLink.CONN_DISCONNECTED) link.disconnect();
            activeConnMac = null;
            if (!a2dpDevs.isEmpty()) {
                BluetoothDevice u = a2dpDevs.get(0);
                detectedDevice = null;
                autoDetectedModel = Protocol.MODEL_UNKNOWN;
                detectedModel = Protocol.MODEL_UNKNOWN;
                unsupportedName = u.getName() != null ? u.getName() : u.getAddress();
                sysStatus = ST_UNSUPPORTED;
                render();
                return;
            }
            String sMac = prefs.getString(K_LAST_MAC, null);
            String sName = prefs.getString(K_LAST_NAME, null);
            int sModel = prefs.getInt(K_LAST_MODEL, Protocol.MODEL_UNKNOWN);
            BluetoothDevice bondedLast = null;
            if (sMac != null) {
                for (BluetoothDevice d : bonded) {
                    if (sMac.equals(d.getAddress())) { bondedLast = d; break; }
                }
            }
            unsupportedName = null;
            if (sMac != null && sName != null) {
                detectedDevice = bondedLast;
                lastDeviceName = (bondedLast != null && bondedLast.getName() != null) ? bondedLast.getName() : sName;
                lastDeviceMac = sMac;
                int autoFromSaved = Protocol.detectModel(lastDeviceName);
                autoDetectedModel = autoFromSaved != Protocol.MODEL_UNKNOWN ? autoFromSaved : sModel;
                detectedModel = manualModelOverride != Protocol.MODEL_UNKNOWN ? manualModelOverride : autoDetectedModel;
                sysStatus = ST_DISCONNECTED;
            } else {
                detectedDevice = null;
                lastDeviceName = null;
                lastDeviceMac = null;
                autoDetectedModel = Protocol.MODEL_UNKNOWN;
                detectedModel = manualModelOverride;
                sysStatus = ST_NO_PAIRED;
            }
        } catch (Exception ignored) {}
        render();
    }

    @Override
    public void onStateChanged(int cs, Protocol.State st) {
        connState = cs;
        budsState = st;
        if (cs == BudsLink.CONN_CONNECTED) {
            sysStatus = ST_ACTIVE;
            reconnectAttempt = 0;
            uiHandler.removeCallbacks(reconnectTask);
        } else if (sysStatus == ST_ACTIVE) {
            sysStatus = ST_CONNECTED_OS;
        }
        render();
    }

    @Override
    public void onUnexpectedDisconnect() {
        if (detectedDevice == null || detectedModel == Protocol.MODEL_UNKNOWN) return;
        if (reconnectAttempt >= 5) return;
        long[] delays = {900L, 1800L, 3200L, 5000L, 5000L};
        long delay = delays[Math.min(reconnectAttempt, delays.length - 1)];
        reconnectAttempt++;
        uiHandler.removeCallbacks(reconnectTask);
        uiHandler.postDelayed(reconnectTask, delay);
    }

    @Override
    public void onLog(String line) {
        logs.addLast(line);
        while (logs.size() > 60) logs.removeFirst();
        updateLogsView();
    }

    // ---------- UI Builder ----------
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setFitsSystemWindows(true);

        // Top App Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(C_SURFACE);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(14), dp(12), dp(14));

        TextView btnMenu = tv("☰", 21, Color.WHITE, true);
        btnMenu.setPadding(dp(10), dp(4), dp(12), dp(4));
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showModelPicker(); }
        });
        topBar.addView(btnMenu);

        tvTopTitle = tv("realness tiny", 20, Color.WHITE, true);
        tvTopTitle.setSingleLine(true);
        tvTopTitle.setEllipsize(TextUtils.TruncateAt.END);
        topBar.addView(tvTopTitle, lpWeight(1));

        btnRefresh = tv("↻", 22, Color.WHITE, true);
        btnRefresh.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { link.requestFullState(true); }
        });
        topBar.addView(btnRefresh);
        root.addView(topBar, lpMatchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(24));

        // 1. Permissions banner
        LinearLayout permBox = card(0xFF332000, 14);
        permBox.addView(tv(t("Bluetooth access required", "Требуется доступ к Bluetooth"), 15, C_ACCENT, true));
        TextView permDesc = tv(t("Grant Nearby devices permission to communicate with the earbuds.",
            "Предоставьте разрешение «Устройства поблизости» для работы с наушниками."), 13, 0xFFCCCCCC, false);
        permDesc.setPadding(0, dp(6), 0, dp(10));
        permBox.addView(permDesc);
        LinearLayout permBtns = hbox();
        TextView bGrant = pillBtn(t("Allow access", "Разрешить"), C_ACCENT, Color.BLACK);
        bGrant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkPermsAndStart(true); }
        });
        TextView bSet = pillBtn(t("Settings", "Настройки"), C_SURFACE2, Color.WHITE);
        bSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            }
        });
        permBtns.addView(bGrant);
        permBtns.addView(spaceW(8));
        permBtns.addView(bSet);
        permBox.addView(permBtns);
        cardPerm = permBox;
        addSpaced(content, cardPerm, 14);

        // 2. Unsupported device banner
        LinearLayout unsupBox = card(0xFF331414, 14);
        unsupBox.addView(tv(t("Unsupported device", "Устройство не поддерживается"), 15, 0xFFEF5350, true));
        tvUnsupportedMsg = tv("", 13, 0xFFCCCCCC, false);
        tvUnsupportedMsg.setPadding(0, dp(6), 0, 0);
        unsupBox.addView(tvUnsupportedMsg);
        cardUnsupported = unsupBox;
        addSpaced(content, cardUnsupported, 14);

        // 3. Status Card
        LinearLayout stCard = card(C_SURFACE, 16);
        LinearLayout stTop = hbox();
        stTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout stLeft = vbox();
        tvDevName = tv("realme Buds", 17, Color.WHITE, true);
        tvDevName.setSingleLine(true);
        tvDevName.setEllipsize(TextUtils.TruncateAt.END);
        tvDevSub = tv("", 12, C_MUTED, false);
        stLeft.addView(tvDevName);
        stLeft.addView(tvDevSub);
        stTop.addView(stLeft, lpWeight(1));
        tvBadge = tv("", 12, Color.WHITE, true);
        tvBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        stTop.addView(tvBadge);
        stCard.addView(stTop, lpMatchWrap());

        tvHint = tv("", 12, 0xFFAAAAAA, false);
        tvHint.setPadding(0, dp(8), 0, 0);
        stCard.addView(tvHint);

        btnReconnect = pillBtn(t("Reconnect to earbuds", "Повторить подключение"), C_ACCENT, Color.BLACK);
        btnReconnect.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = lpMatchWrap();
        rlp.topMargin = dp(10);
        btnReconnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (detectedDevice != null && detectedModel != Protocol.MODEL_UNKNOWN) {
                    connectDevice(detectedDevice, detectedModel);
                }
            }
        });
        stCard.addView(btnReconnect, rlp);

        LinearLayout actRow = hbox();
        LinearLayout.LayoutParams alp = lpMatchWrap();
        alp.topMargin = dp(10);
        btnFind = pillBtn(t("Find earbuds", "Поиск наушников"), C_SURFACE2, Color.WHITE);
        btnFind.setGravity(Gravity.CENTER);
        btnFind.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFindDialog(); }
        });
        btnRename = pillBtn(t("Rename", "Переименовать"), C_SURFACE2, Color.WHITE);
        btnRename.setGravity(Gravity.CENTER);
        btnRename.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRenameDialog(); }
        });
        actRow.addView(btnFind, lpWeight(1));
        actRow.addView(spaceW(8));
        actRow.addView(btnRename, lpWeight(1));
        stCard.addView(actRow, alp);
        addSpaced(content, stCard, 16);

        // 4. Battery section
        addSection(content, t("Battery level", "Уровень заряда"));
        LinearLayout batRow = hbox();
        View[] bl = makeBatCard(t("Left", "Левый"));
        tvBatL = (TextView) bl[1]; barBatL = bl[2];
        View[] bc = makeBatCard(t("Case", "Кейс"));
        tvBatC = (TextView) bc[1]; barBatC = bc[2];
        View[] br = makeBatCard(t("Right", "Правый"));
        tvBatR = (TextView) br[1]; barBatR = br[2];
        batRow.addView(bl[0], lpWeight(1));
        batRow.addView(spaceW(8));
        batRow.addView(bc[0], lpWeight(1));
        batRow.addView(spaceW(8));
        batRow.addView(br[0], lpWeight(1));
        addSpaced(content, batRow, 16);

        // 5. ANC Section
        secAnc = addSection(content, t("Noise cancellation", "Шумоподавление"));
        LinearLayout ancCard = card(C_SURFACE, 16);
        ancCard.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout ancRow = hbox();
        String[] ancLabels = {
            t("Noise cancellation", "Шумоподавление"),
            t("Off", "Выкл."),
            t("Transparency", "Прозрачность")
        };
        final byte[] ancModes = {Protocol.ANC_ON, Protocol.ANC_OFF, Protocol.ANC_TRANSPARENCY};
        float[] ancW = {1.35f, 0.6f, 1.05f};
        for (int i = 0; i < 3; i++) {
            final byte m = ancModes[i];
            TextView b = segBtn(ancLabels[i], 12);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (connState == BudsLink.CONN_CONNECTED) link.setAnc(m); }
            });
            btnAnc[i] = b;
            ancRow.addView(b, lpWeight(ancW[i]));
            if (i < 2) ancRow.addView(spaceW(4));
        }
        ancCard.addView(ancRow, lpMatchWrap());

        // Huawei ANC intensity level sub-row
        LinearLayout lvlRow = hbox();
        lvlRow.setBackground(roundRect(0xFF262626, 10));
        lvlRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] lvlNames = {t("Comfort", "Комфорт"), t("General", "Обычный"), t("Ultra", "Ультра"), t("Dynamic", "Динам.")};
        final byte[] lvlBytes = {1, 0, 2, 3};
        for (int i = 0; i < 4; i++) {
            final byte lb = lvlBytes[i];
            TextView b = segBtn(lvlNames[i], 11);
            b.setPadding(dp(3), dp(8), dp(3), dp(8));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (connState == BudsLink.CONN_CONNECTED) link.setAncLevel(lb); }
            });
            btnAncLvl[i] = b;
            lvlRow.addView(b, lpWeight(1));
            if (i < 3) lvlRow.addView(spaceW(4));
        }
        LinearLayout.LayoutParams lvllp = lpMatchWrap();
        lvllp.topMargin = dp(6);
        lvlRow.setVisibility(View.GONE);
        ancCard.addView(lvlRow, lvllp);
        rowAncLvl = lvlRow;
        cardAnc = ancCard;
        addSpaced(content, ancCard, 16);

        // 6. EQ Section
        secEq = addSection(content, t("Sound effects", "Звуковые эффекты"));
        LinearLayout eqCard = card(C_SURFACE, 16);
        String[] eqLabels = {
            t("Serenade", "Серенада"),
            t("Original sound", "Оригинальный звук"),
            t("Pure bass", "Чистые басы"),
            t("Deep bass", "Глубокие басы")
        };
        final byte[] eqIds = {0x02, 0x00, 0x03, 0x01};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = hbox();
            for (int c = 0; c < 2; c++) {
                int idx = r * 2 + c;
                final byte id = eqIds[idx];
                TextView b = segBtn(eqLabels[idx], 13);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { if (connState == BudsLink.CONN_CONNECTED) link.setEq(id); }
                });
                btnEq[idx] = b;
                row.addView(b, lpWeight(1));
                if (c == 0) row.addView(spaceW(8));
            }
            if (r == 1) row.setPadding(0, dp(8), 0, 0);
            eqCard.addView(row, lpMatchWrap());
        }
        tvCustomEq = tv("", 12, C_ACCENT, true);
        tvCustomEq.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvCustomEq.setBackground(roundRect(0x26FFCC00, 8));
        LinearLayout.LayoutParams clp = lpMatchWrap();
        clp.topMargin = dp(8);
        eqCard.addView(tvCustomEq, clp);

        btnCustomEq = pillBtn(t("Custom equalizer", "Настраиваемый эквалайзер"), C_SURFACE2, C_ACCENT);
        btnCustomEq.setGravity(Gravity.CENTER);
        btnCustomEq.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (connState == BudsLink.CONN_CONNECTED) showCustomEqDialog(); }
        });
        LinearLayout.LayoutParams celp = lpMatchWrap();
        celp.topMargin = dp(8);
        eqCard.addView(btnCustomEq, celp);
        cardEq = eqCard;
        addSpaced(content, eqCard, 16);

        // 7. Button Settings
        secTouch = addSection(content, t("Button settings", "Настройка кнопок"));
        LinearLayout keyCard = card(C_SURFACE, 16);
        LinearLayout sideBar = hbox();
        sideBar.setBackground(roundRect(C_SURFACE2, 10));
        sideBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] sideNames = {t("Left earbud", "Левый наушник"), t("Right earbud", "Правый наушник")};
        for (int i = 0; i < 2; i++) {
            final int s = i;
            TextView b = segBtn(sideNames[i], 13);
            b.setPadding(dp(4), dp(8), dp(4), dp(8));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectedSide = s; render(); }
            });
            btnSide[i] = b;
            sideBar.addView(b, lpWeight(1));
            if (i == 0) sideBar.addView(spaceW(4));
        }
        keyCard.addView(sideBar, lpMatchWrap());

        String[] gestNames = {
            t("Double tap", "Двойное касание"),
            t("Triple tap", "Тройное касание"),
            t("Touch & hold", "Касание и удержание")
        };
        for (int g = 0; g < 3; g++) {
            final int gi = g;
            LinearLayout row = hbox();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(12), dp(6), dp(12));
            LinearLayout left = vbox();
            left.addView(tv(gestNames[g], 14, Color.WHITE, false));
            tvGestVal[g] = tv("—", 12, C_ACCENT, false);
            left.addView(tvGestVal[g]);
            row.addView(left, lpWeight(1));
            row.addView(tv("›", 20, C_MUTED, false));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (connState == BudsLink.CONN_CONNECTED) showGestureDialog(selectedSide, gi);
                }
            });
            rowGest[g] = row;
            keyCard.addView(row, lpMatchWrap());
        }
        cardTouch = keyCard;
        addSpaced(content, keyCard, 16);

        // 8. Spatial Audio
        secSpatial = addSection(content, t("Spatial audio", "Пространственное аудио"));
        LinearLayout spCard = card(C_SURFACE, 16);
        spCard.addView(tv(t("Creates a cinematic 3D audio experience around you.",
            "Создаёт кинематографичный трёхмерный звук вокруг вас."), 12, C_MUTED, false));
        LinearLayout spRow = hbox();
        spRow.setBackground(roundRect(C_SURFACE2, 10));
        spRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] spLabels = {t("Off", "Выкл."), t("On", "Включить")};
        for (int i = 0; i < 2; i++) {
            final boolean on = (i == 1);
            TextView b = segBtn(spLabels[i], 13);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (connState == BudsLink.CONN_CONNECTED) link.setSwitch(Protocol.FEAT_SPATIAL, on);
                }
            });
            btnSpatial[i] = b;
            spRow.addView(b, lpWeight(1));
            if (i == 0) spRow.addView(spaceW(4));
        }
        LinearLayout.LayoutParams splp = lpMatchWrap();
        splp.topMargin = dp(10);
        spCard.addView(spRow, splp);
        cardSpatial = spCard;
        addSpaced(content, spCard, 14);

        // 9. High-Definition Audio (LDAC / LHDC 5.0 / Huawei)
        Object[] hq = makeSwitchCard(
            t("High-definition audio", "Звук высокой чёткости"),
            t("LDAC codec for superior audio quality.", "Кодек LDAC, обеспечивающий превосходное качество звука."),
            Protocol.FEAT_HQ_AUDIO);
        cardHq = (View) hq[0]; swHq = (Switch) hq[1]; tvHqDesc = (TextView) hq[2];
        addSpaced(content, cardHq, 12);

        // 10. Dynamic Audio / Enhanced Bass (levels 1..5 on Nothing/CMF)
        Object[] da = makeSwitchCard(
            t("Dynamic audio / Ultra Bass", "Динамическое аудио / Бас"),
            t("Dynamically enhances bass and sound depth.", "Динамически усиливает басы и глубину звучания."),
            Protocol.FEAT_DYNAMIC);
        LinearLayout dynCard = (LinearLayout) da[0];
        cardDynamic = dynCard; swDynamic = (Switch) da[1];
        LinearLayout bassRow = hbox();
        bassRow.setBackground(roundRect(C_SURFACE2, 10));
        bassRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < 5; i++) {
            final int lvl = i + 1;
            TextView b = segBtn(String.valueOf(lvl), 12);
            b.setPadding(dp(2), dp(7), dp(2), dp(7));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (connState == BudsLink.CONN_CONNECTED) link.setBassLevel(lvl);
                }
            });
            btnBassLvl[i] = b;
            bassRow.addView(b, lpWeight(1));
            if (i < 4) bassRow.addView(spaceW(3));
        }
        LinearLayout.LayoutParams blp = lpMatchWrap();
        blp.topMargin = dp(8);
        dynCard.addView(bassRow, blp);
        rowBassLvl = bassRow;
        addSpaced(content, cardDynamic, 16);

        // 11. Other Section
        secOther = addSection(content, t("Other", "Другое"));

        // Game Mode
        Object[] gm = makeSwitchCard(
            t("Game mode", "Игровой режим"),
            t("Provides smooth gameplay with ultra-low audio latency.", "Обеспечивает плавный игровой процесс с минимальной задержкой звука."),
            Protocol.FEAT_GAME_MODE);
        cardGame = (View) gm[0];
        swGame = (Switch) gm[1];
        addSpaced(content, cardGame, 12);

        // Dual-Device Connection + History
        Object[] dc = makeSwitchCard(
            t("Dual-device connection", "Подключение к двум устройствам"),
            t("Connect to 2 devices simultaneously and switch seamlessly between them.",
              "Подключите одновременно 2 устройства и легко переключайтесь между ними."),
            Protocol.FEAT_DUAL_CONN);
        LinearLayout dualCard = (LinearLayout) dc[0];
        cardDual = dualCard;
        swDual = (Switch) dc[1];
        multiListBox = vbox();
        dualCard.addView(multiListBox, lpMatchWrap());
        addSpaced(content, cardDual, 12);

        // Auto Play/Pause (Air 7 Pro)
        Object[] ap = makeSwitchCard(
            t("Auto play/pause", "Автовоспроизведение/пауза"),
            t("Remove either earbud to pause playback, and put it back on to resume.",
              "Чтобы приостановить воспроизведение, снимите любой наушник, а чтобы возобновить воспроизведение, наденьте его снова."),
            Protocol.FEAT_AUTO_PLAY_PAUSE);
        cardAutoPlay = (View) ap[0]; swAutoPlay = (Switch) ap[1];
        addSpaced(content, cardAutoPlay, 12);

        // Auto Answer (Air 7 Pro)
        Object[] aa = makeSwitchCard(
            t("Auto answer", "Автоответ"),
            t("Take the earbuds out of the charging case and put them on to answer a call.",
              "Чтобы ответить на вызов, извлеките наушники из футляра для зарядки и наденьте их."),
            Protocol.FEAT_AUTO_ANSWER);
        cardAutoAns = (View) aa[0]; swAutoAns = (Switch) aa[1];
        addSpaced(content, cardAutoAns, 14);

        // 12. Usage Time Card
        LinearLayout useCard = card(C_SURFACE, 16);
        useCard.addView(tv(t("Total usage", "Всего"), 14, Color.WHITE, true));
        LinearLayout useRow = hbox();
        useRow.setPadding(0, dp(10), 0, 0);
        tvUseL = makeUseBadge(useRow, "L");
        useRow.addView(spaceW(16));
        tvUseR = makeUseBadge(useRow, "R");
        useCard.addView(useRow, lpMatchWrap());
        cardUseTime = useCard;
        addSpaced(content, cardUseTime, 14);

        // 13. Collapsible Protocol Logs Console
        LinearLayout logCard = card(C_SURFACE, 14);
        LinearLayout logHdr = hbox();
        logHdr.setGravity(Gravity.CENTER_VERTICAL);
        tvLogsHeader = tv(t("Protocol logs (0)", "Протокольный лог (0)"), 13, Color.WHITE, true);
        logHdr.addView(tvLogsHeader, lpWeight(1));

        TextView bCopy = tv(t("Copy", "Копир."), 12, C_ACCENT, true);
        bCopy.setPadding(dp(8), dp(4), dp(8), dp(4));
        bCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String l : logs) sb.append(l).append('\n');
                    cm.setPrimaryClip(ClipData.newPlainText("logs", sb.toString()));
                    Toast.makeText(MainActivity.this,
                        t("Logs copied (" + logs.size() + ")", "Логи скопированы (" + logs.size() + ")"),
                        Toast.LENGTH_SHORT).show();
                }
            }
        });
        TextView bClear = tv(t("Clear", "Очист."), 12, C_MUTED, false);
        bClear.setPadding(dp(8), dp(4), dp(8), dp(4));
        bClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logs.clear(); updateLogsView(); }
        });
        btnLogToggle = tv("▼", 12, C_MUTED, false);
        btnLogToggle.setPadding(dp(8), dp(4), dp(4), dp(4));
        logHdr.addView(bCopy);
        logHdr.addView(bClear);
        logHdr.addView(btnLogToggle);
        logHdr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                logsExpanded = !logsExpanded;
                logsScroll.setVisibility(logsExpanded ? View.VISIBLE : View.GONE);
                btnLogToggle.setText(logsExpanded ? "▲" : "▼");
            }
        });
        logCard.addView(logHdr, lpMatchWrap());

        logsScroll = new ScrollView(this);
        logsScroll.setBackground(roundRect(0xFF0A0A0A, 10));
        logsScroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvLogsBody = tv("", 10, 0xFF81C784, false);
        tvLogsBody.setTypeface(Typeface.MONOSPACE);
        logsScroll.addView(tvLogsBody, lpMatchWrap());
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        lslp.topMargin = dp(8);
        logsScroll.setVisibility(View.GONE);
        logCard.addView(logsScroll, lslp);
        addSpaced(content, logCard, 12);

        // Footer version + build (read dynamically from manifest)
        String verText = "tiny";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            verText = "v" + pi.versionName + " · build " + vc + " · tiny";
        } catch (Exception ignored) {}
        TextView footer = tv(verText, 11, 0xFF555555, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(4), 0, dp(8));
        content.addView(footer, lpMatchWrap());

        scroll.addView(content, lpMatchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void render() {
        updatingUi = true;
        boolean connected = (connState == BudsLink.CONN_CONNECTED);

        String rawName = (detectedDevice != null && detectedDevice.getName() != null)
            ? detectedDevice.getName() : lastDeviceName;
        String dispMac = detectedDevice != null ? detectedDevice.getAddress() : lastDeviceMac;
        customAlias = dispMac != null ? prefs.getString(K_ALIAS_PREFIX + dispMac, null) : null;
        String dispName = customAlias != null ? customAlias : rawName;

        tvTopTitle.setText(dispName != null ? dispName : "realness tiny");
        btnRefresh.setVisibility(connected ? View.VISIBLE : View.GONE);
        boolean ringing = (findTone != null && findTone.isPlaying()) || hwFindActive;
        btnFind.setText(ringing ? t("Ringing…", "Звучит…") : t("Find earbuds", "Поиск наушников"));
        btnFind.setBackground(roundRect(ringing ? C_RED : C_SURFACE2, 20));
        btnRename.setVisibility((dispMac != null || detectedDevice != null) ? View.VISIBLE : View.GONE);

        cardPerm.setVisibility(hasPerms() ? View.GONE : View.VISIBLE);
        boolean showUnsup = (sysStatus == ST_UNSUPPORTED && unsupportedName != null);
        cardUnsupported.setVisibility(showUnsup ? View.VISIBLE : View.GONE);
        if (showUnsup) {
            tvUnsupportedMsg.setText(t(
                "\"" + unsupportedName + "\" was not recognized automatically. You can pick a model manually via ☰.",
                "«" + unsupportedName + "» не определилось автоматически. Можно выбрать модель вручную через ☰."));
        }

        // Status Card
        String name = dispName != null ? dispName
            : (unsupportedName != null ? unsupportedName : t("No device connected", "Нет подключённых устройств"));
        tvDevName.setText(name);
        String subTxt = dispMac != null ? dispMac
            : (sysStatus == ST_UNSUPPORTED ? t("Unsupported device", "Устройство не поддерживается")
            : t("Connect earbuds via Bluetooth", "Подключите наушники по Bluetooth"));
        if (manualModelOverride != Protocol.MODEL_UNKNOWN) {
            subTxt = subTxt + " · " + Protocol.modelTitle(manualModelOverride);
        }
        tvDevSub.setText(subTxt);

        String badgeTxt; int badgeCol;
        if (connected) { badgeTxt = t("Connected", "Подключено"); badgeCol = C_GREEN; }
        else if (connState == BudsLink.CONN_CONNECTING) { badgeTxt = t("Connecting…", "Подключение…"); badgeCol = C_ORANGE; }
        else if (sysStatus == ST_CONNECTED_OS) { badgeTxt = t("Online (connecting)", "В сети (подключение)"); badgeCol = C_ORANGE; }
        else if (sysStatus == ST_UNSUPPORTED) { badgeTxt = t("Unsupported", "Не поддерживается"); badgeCol = C_RED; }
        else if (sysStatus == ST_NO_PAIRED) { badgeTxt = t("Disconnected", "Отключено"); badgeCol = C_MUTED; }
        else if (sysStatus == ST_NO_PERM) { badgeTxt = t("No permissions", "Нет прав"); badgeCol = C_RED; }
        else { badgeTxt = t("In case / disconnected", "В кейсе / отключено"); badgeCol = C_MUTED; }
        tvBadge.setText(badgeTxt);
        tvBadge.setTextColor(badgeCol);
        tvBadge.setBackground(roundRect((badgeCol & 0x00FFFFFF) | 0x33000000, 12));

        String hint = null;
        if (connected) hint = null;
        else if (connState == BudsLink.CONN_CONNECTING) hint = t("Connecting via SPP/GATT…", "Установка соединения через SPP/GATT…");
        else if (sysStatus == ST_UNSUPPORTED) hint = t("Tap ☰ to try selecting a compatible protocol manually.", "Нажмите ☰, чтобы выбрать совместимый протокол вручную.");
        else if (sysStatus == ST_DISCONNECTED) hint = t("Open the case or put on the earbuds.", "Откройте кейс или наденьте наушники.");
        else if (sysStatus == ST_NO_PAIRED) hint = t("Connect earbuds via Bluetooth or pick a model via ☰.", "Подключите наушники по Bluetooth или выберите модель через ☰.");
        else if (sysStatus == ST_NO_PERM) hint = t("Grant Bluetooth permissions above.", "Предоставьте доступ к Bluetooth выше.");
        tvHint.setVisibility(hint != null ? View.VISIBLE : View.GONE);
        if (hint != null) tvHint.setText(hint);

        btnReconnect.setVisibility((detectedDevice != null && !connected && sysStatus == ST_CONNECTED_OS) ? View.VISIBLE : View.GONE);

        // Batteries + persist/display last known Case battery
        String batMac = detectedDevice != null ? detectedDevice.getAddress() : lastDeviceMac;
        if (batMac != null && budsState.caseBat >= 0 && budsState.caseBat <= 100) {
            prefs.edit()
                .putInt(K_LAST_CASE_LVL + batMac, budsState.caseBat)
                .putLong(K_LAST_CASE_TS + batMac, System.currentTimeMillis())
                .apply();
        }
        updateBat(tvBatL, barBatL, budsState.leftBat, budsState.leftChg);
        if (budsState.caseBat >= 0) {
            updateBat(tvBatC, barBatC, budsState.caseBat, budsState.caseChg);
        } else if (batMac != null && prefs.getInt(K_LAST_CASE_LVL + batMac, -1) >= 0) {
            int lastLvl = prefs.getInt(K_LAST_CASE_LVL + batMac, -1);
            long lastTs = prefs.getLong(K_LAST_CASE_TS + batMac, 0L);
            updateBat(tvBatC, barBatC, lastLvl, false);
            if (lastTs > 0L) {
                tvBatC.setText(lastLvl + "% (" + fmtAgo(lastTs) + ")");
                tvBatC.setTextColor(C_MUTED);
            }
        } else {
            updateBat(tvBatC, barBatC, -1, false);
        }
        updateBat(tvBatR, barBatR, budsState.rightBat, budsState.rightChg);

        boolean known = detectedModel != Protocol.MODEL_UNKNOWN;
        boolean isHw = Protocol.isHuawei(detectedModel);
        boolean isHc = Protocol.isHonorChoice(detectedModel);
        boolean isNo = Protocol.isNothing(detectedModel);
        boolean isGx = Protocol.isGalaxy(detectedModel);
        boolean isSn = Protocol.isSony(detectedModel);
        boolean isXi = Protocol.isXiaomi(detectedModel);
        boolean isJb = Protocol.isJbl(detectedModel);

        // ANC
        int ancVis = (!known || Protocol.supportsAnc(detectedModel)) ? View.VISIBLE : View.GONE;
        secAnc.setVisibility(ancVis); cardAnc.setVisibility(ancVis);
        int[] ancVals = {Protocol.ANC_ON, Protocol.ANC_OFF, Protocol.ANC_TRANSPARENCY};
        for (int i = 0; i < 3; i++) {
            styleSeg(btnAnc[i], budsState.ancMode == ancVals[i], connected, Color.TRANSPARENT);
        }
        boolean showAncLvl = Protocol.supportsAncLevel(detectedModel) && budsState.ancMode == Protocol.ANC_ON;
        rowAncLvl.setVisibility(showAncLvl ? View.VISIBLE : View.GONE);
        if (showAncLvl) {
            String[] lvlNames = (isNo || isXi)
                ? new String[]{t("Low", "Низк."), t("Mid", "Сред."), t("High", "Выс."), t("Adaptive", "Адапт.")}
                : new String[]{t("Comfort", "Комфорт"), t("General", "Обычный"), t("Ultra", "Ультра"), t("Dynamic", "Динам.")};
            btnAncLvl[3].setVisibility(Protocol.supportsAncDynamic(detectedModel) ? View.VISIBLE : View.GONE);
            int[] lvlVals = {1, 0, 2, 3};
            for (int i = 0; i < 4; i++) {
                btnAncLvl[i].setText(lvlNames[i]);
                boolean sel = budsState.ancLevel == lvlVals[i];
                btnAncLvl[i].setBackground(roundRect(sel ? 0x38FFCC00 : Color.TRANSPARENT, 8));
                btnAncLvl[i].setTextColor(sel ? C_ACCENT : (connected ? 0xFFCCCCCC : C_MUTED));
                btnAncLvl[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            }
        }

        // EQ
        boolean hasCustomEq = Protocol.supportsCustomEq(detectedModel);
        int eqVis = (!known || Protocol.supportsEq(detectedModel) || hasCustomEq) ? View.VISIBLE : View.GONE;
        secEq.setVisibility(eqVis); cardEq.setVisibility(eqVis);
        btnCustomEq.setVisibility(hasCustomEq ? View.VISIBLE : View.GONE);
        btnCustomEq.setAlpha(connected ? 1f : 0.45f);
        String[] eqNames;
        if (isHw || isXi) {
            eqNames = new String[]{t("Voices", "Голоса"), t("Default", "По умолчанию"), t("Treble boost", "Высокие частоты"), t("Bass boost", "Усиление басов")};
        } else if (isHc) {
            eqNames = new String[]{t("Voices", "Голоса"), t("Balanced", "Баланс"), t("Bass boost", "Усиление басов"), t("Bass boost", "Усиление басов")};
        } else if (isNo && Protocol.isNothingListeningEq(detectedModel)) {
            eqNames = new String[]{t("Vocals", "Вокал"), t("Pop", "Поп"), t("Electronic", "Электроника"), t("Rock", "Рок")};
        } else if (isNo) {
            eqNames = new String[]{t("Voice", "Голос"), t("Balanced", "Баланс"), t("More treble", "Больше высоких"), t("More bass", "Больше басов")};
        } else if (isGx) {
            eqNames = new String[]{t("Clear", "Чёткий"), t("Normal", "Обычный"), t("Dynamic", "Динамичный"), t("Bass boost", "Усиление басов")};
        } else if (isSn) {
            eqNames = new String[]{t("Voices", "Голоса"), t("Off", "Выкл."), t("Bright", "Яркий"), t("Bass boost", "Усиление басов")};
        } else if (isJb) {
            eqNames = new String[]{t("Voices", "Голоса"), t("Studio", "Студия"), t("Club", "Клуб"), t("Bass boost", "Усиление басов")};
        } else {
            eqNames = new String[]{t("Serenade", "Серенада"), t("Original sound", "Оригинальный звук"), t("Pure bass", "Чистые басы"), t("Deep bass", "Глубокие басы")};
        }
        int[] eqIds = {2, 0, 3, 1};
        for (int i = 0; i < 4; i++) {
            btnEq[i].setVisibility((isHc && i == 2) ? View.GONE : View.VISIBLE);
            btnEq[i].setText(eqNames[i]);
            boolean sel = (budsState.eqId == eqIds[i]) && budsState.customEqName == null;
            styleSeg(btnEq[i], sel, connected, C_SURFACE2);
        }
        tvCustomEq.setVisibility(budsState.customEqName != null ? View.VISIBLE : View.GONE);
        if (budsState.customEqName != null) {
            tvCustomEq.setText(t("Custom: " + budsState.customEqName + " (active)",
                "Пользовательский: " + budsState.customEqName + " (активен)"));
        }

        // Touch settings
        int touchVis = (!known || Protocol.supportsTouch(detectedModel)) ? View.VISIBLE : View.GONE;
        secTouch.setVisibility(touchVis); cardTouch.setVisibility(touchVis);
        rowGest[0].setVisibility((!known || Protocol.supportsDoubleTap(detectedModel)) ? View.VISIBLE : View.GONE);
        rowGest[1].setVisibility((!known || Protocol.supportsTripleTap(detectedModel)) ? View.VISIBLE : View.GONE);
        rowGest[2].setVisibility((!known || Protocol.supportsLongTap(detectedModel)) ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2; i++) styleSeg(btnSide[i], selectedSide == i, connected, Color.TRANSPARENT);
        for (int g = 0; g < 3; g++) {
            int fn = budsState.touch[selectedSide][g];
            tvGestVal[g].setText(fn >= 0 ? actionLabel(fn) : "—");
            tvGestVal[g].setTextColor(connected ? C_ACCENT : C_MUTED);
        }

        // Spatial
        int spVis = (!known || Protocol.supportsSpatial(detectedModel)) ? View.VISIBLE : View.GONE;
        secSpatial.setVisibility(spVis); cardSpatial.setVisibility(spVis);
        styleSeg(btnSpatial[0], Boolean.FALSE.equals(budsState.spatialAudio), connected, Color.TRANSPARENT);
        styleSeg(btnSpatial[1], Boolean.TRUE.equals(budsState.spatialAudio), connected, Color.TRANSPARENT);

        // Model-gated cards
        cardHq.setVisibility(Protocol.supportsHqAudio(detectedModel) ? View.VISIBLE : View.GONE);
        if (Protocol.supportsHqAudio(detectedModel)) {
            tvHqDesc.setText(isHw
                ? t("Prioritize sound quality over connection stability.", "Приоритет качества звука над стабильностью соединения.")
                : (Protocol.usesLhdc(detectedModel)
                    ? t("Play high-resolution LHDC 5.0 audio.", "Воспроизводите аудио в высоком разрешении LHDC 5.0.")
                    : t("LDAC codec for superior audio quality.", "Кодек LDAC, обеспечивающий превосходное качество звука.")));
        }
        setSwitchUi(swHq, budsState.hqAudio, connected);

        cardDynamic.setVisibility(Protocol.supportsDynamic(detectedModel) ? View.VISIBLE : View.GONE);
        setSwitchUi(swDynamic, budsState.dynamicAudio, connected);
        boolean showBassLvl = isNo && Boolean.TRUE.equals(budsState.dynamicAudio);
        rowBassLvl.setVisibility(showBassLvl ? View.VISIBLE : View.GONE);
        if (showBassLvl) {
            for (int i = 0; i < 5; i++) {
                boolean sel = budsState.bassLevel == (i + 1);
                btnBassLvl[i].setBackground(roundRect(sel ? 0x38FFCC00 : Color.TRANSPARENT, 8));
                btnBassLvl[i].setTextColor(sel ? C_ACCENT : (connected ? 0xFFCCCCCC : C_MUTED));
                btnBassLvl[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            }
        }

        boolean showGame = !known || Protocol.supportsGameMode(detectedModel);
        boolean showDual = Protocol.supportsDual(detectedModel);
        boolean showAp = Protocol.supportsAutoPlayPause(detectedModel);
        boolean showAa = Protocol.supportsAutoAnswer(detectedModel);
        secOther.setVisibility((showGame || showDual || showAp || showAa) ? View.VISIBLE : View.GONE);
        cardGame.setVisibility(showGame ? View.VISIBLE : View.GONE);
        setSwitchUi(swGame, budsState.gameMode, connected);

        cardDual.setVisibility(showDual ? View.VISIBLE : View.GONE);
        setSwitchUi(swDual, budsState.dualConn, connected);
        renderMultiDevices();

        cardAutoPlay.setVisibility(showAp ? View.VISIBLE : View.GONE);
        setSwitchUi(swAutoPlay, budsState.autoPlayPause, connected);

        cardAutoAns.setVisibility(showAa ? View.VISIBLE : View.GONE);
        setSwitchUi(swAutoAns, budsState.autoAnswer, connected);

        // Usage time
        boolean hasUse = budsState.leftUseMin >= 0 || budsState.rightUseMin >= 0;
        cardUseTime.setVisibility(hasUse ? View.VISIBLE : View.GONE);
        if (hasUse) {
            tvUseL.setText(fmtMinutes(budsState.leftUseMin));
            tvUseR.setText(fmtMinutes(budsState.rightUseMin));
        }

        updatingUi = false;
    }

    private void renderMultiDevices() {
        multiListBox.removeAllViews();
        if (budsState.multiDevices.isEmpty()) return;
        View div = new View(this);
        div.setBackgroundColor(C_SURFACE2);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10); dlp.bottomMargin = dp(8);
        multiListBox.addView(div, dlp);

        TextView hdr = tv(t("Connection history", "История подключений"), 12, C_MUTED, false);
        hdr.setPadding(0, 0, 0, dp(6));
        multiListBox.addView(hdr);

        for (Protocol.MultiDevice d : budsState.multiDevices) {
            LinearLayout row = hbox();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            LinearLayout left = vbox();
            left.addView(tv(d.name.isEmpty() ? d.mac : d.name, 13, Color.WHITE, false));
            left.addView(tv(d.mac, 11, C_MUTED, false));
            row.addView(left, lpWeight(1));
            boolean act = d.state != 0;
            row.addView(tv(act ? t("Connected", "Подключено") : t("Disconnected", "Отключено"),
                12, act ? C_GREEN : C_MUTED, act));
            multiListBox.addView(row, lpMatchWrap());
        }
    }

    private void showGestureDialog(final int side, final int gest) {
        final byte[] allowed;
        if (Protocol.isHuawei(detectedModel)) {
            if (gest == 2) {
                allowed = (detectedModel == Protocol.MODEL_HW_CLIP2 || detectedModel == Protocol.MODEL_HW_SE4)
                    ? Protocol.HW_ACTIONS_HOLD_EXTRA : Protocol.HW_ACTIONS_HOLD;
            } else {
                allowed = detectedModel == Protocol.MODEL_HW_3 ? Protocol.HW_ACTIONS_FB3 : Protocol.HW_ACTIONS_TAP;
            }
        } else {
            allowed = gest == 0 ? Protocol.ACTIONS_DOUBLE
                : (gest == 1 ? Protocol.ACTIONS_TRIPLE : Protocol.ACTIONS_HOLD);
        }
        String[] labels = new String[allowed.length];
        for (int i = 0; i < allowed.length; i++) labels[i] = actionLabel(allowed[i] & 0xFF);
        String title = (side == 0 ? t("Left", "Левый") : t("Right", "Правый")) + ": " +
            (gest == 0 ? t("Double tap", "Двойное касание")
            : (gest == 1 ? t("Triple tap", "Тройное касание") : t("Touch & hold", "Касание и удержание")));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    link.setKey(side, gest, allowed[which]);
                }
            })
            .setNegativeButton(t("Cancel", "Отмена"), null)
            .show();
    }

    private void showFindDialog() {
        LinearLayout box = vbox();
        box.setPadding(dp(20), dp(14), dp(20), dp(8));
        box.addView(tv(t("Do not wear the earbuds while playing the locator tone.",
            "Не надевайте наушники во время сигнала поиска."), 12, C_MUTED, false));

        final String[] chNames = {t("Left", "Левый"), t("Both", "Оба"), t("Right", "Правый")};
        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        rg.setPadding(0, dp(10), 0, dp(10));
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(chNames[i]);
            rb.setTextColor(Color.WHITE);
            rg.addView(rb);
        }
        rg.check(1000 + findTone.getChannel());
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                int ch = id - 1000;
                findTone.setChannel(ch);
                if (hwFindActive && Protocol.isNothing(detectedModel)) link.setHardwareFindBuds(true, ch);
            }
        });
        box.addView(rg);

        if (Protocol.supportsHwFind(detectedModel) && connState == BudsLink.CONN_CONNECTED) {
            final CheckBox cbHw = new CheckBox(this);
            cbHw.setText(t("Built-in earbud chime", "Встроенный сигнал наушников"));
            cbHw.setTextColor(Color.WHITE);
            cbHw.setChecked(hwFindActive);
            cbHw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                    hwFindActive = c;
                    link.setHardwareFindBuds(c, findTone.getChannel());
                    render();
                }
            });
            box.addView(cbHw);
        }

        boolean playing = findTone.isPlaying();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Find earbuds", "Поиск наушников"))
            .setView(box)
            .setPositiveButton(playing ? t("Stop", "Остановить") : t("Play sound", "Включить звук"),
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (findTone.isPlaying()) {
                            findTone.stop();
                            if (hwFindActive) { hwFindActive = false; link.setHardwareFindBuds(false, FindTone.CH_BOTH); }
                        } else {
                            findTone.start(findTone.getChannel());
                        }
                        render();
                    }
                })
            .setNegativeButton(t("Cancel", "Отмена"), null)
            .show();
    }

    private void showRenameDialog() {
        final String mac = detectedDevice != null ? detectedDevice.getAddress() : lastDeviceMac;
        if (mac == null) return;
        String cur = customAlias != null ? customAlias
            : (detectedDevice != null && detectedDevice.getName() != null ? detectedDevice.getName() : lastDeviceName);
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(cur != null ? cur : "");
        et.setTextColor(Color.WHITE);
        et.setHint(t("Leave empty to restore default", "Пусто = исходное имя"));
        LinearLayout box = vbox();
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        box.addView(et);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Rename earbuds", "Переименовать наушники"))
            .setView(box)
            .setPositiveButton(t("Rename", "Переименовать"), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String nm = et.getText().toString().trim();
                    if (nm.isEmpty()) {
                        prefs.edit().remove(K_ALIAS_PREFIX + mac).apply();
                        customAlias = null;
                        if (detectedDevice != null) tryOsSetAlias(detectedDevice, null);
                        Toast.makeText(MainActivity.this, t("Default name restored", "Исходное имя восстановлено"), Toast.LENGTH_SHORT).show();
                    } else {
                        boolean fw = link.renameFirmware(nm);
                        boolean osOk = detectedDevice != null && tryOsSetAlias(detectedDevice, nm);
                        prefs.edit().putString(K_ALIAS_PREFIX + mac, nm).apply();
                        customAlias = nm;
                        if (fw) {
                            Toast.makeText(MainActivity.this, t("Name sent to earbud firmware", "Имя отправлено в прошивку"), Toast.LENGTH_SHORT).show();
                        } else if (osOk) {
                            Toast.makeText(MainActivity.this, t("Bluetooth name updated in Android", "Имя Bluetooth обновлено в Android"), Toast.LENGTH_SHORT).show();
                        } else if (detectedDevice != null && Build.VERSION.SDK_INT >= 31) {
                            requestCdmAssociateForRename(detectedDevice, nm);
                        } else {
                            Toast.makeText(MainActivity.this, t("Display name saved in realness", "Имя сохранено в realness"), Toast.LENGTH_SHORT).show();
                        }
                    }
                    render();
                }
            })
            .setNegativeButton(t("Cancel", "Отмена"), null)
            .show();
    }

    private void showCustomEqDialog() {
        final String[] labels;
        final int maxDb;
        if (Protocol.isXiaomi(detectedModel)) {
            labels = new String[]{"62", "125", "250", "500", "1k", "2k", "4k", "8k", "12k", "16k"};
            maxDb = 10;
        } else if (Protocol.isSony(detectedModel)) {
            labels = new String[]{"Bass", "400", "1k", "2.5k", "6.3k", "16k"};
            maxDb = 10;
        } else if (Protocol.isBose(detectedModel) || Protocol.isNothing(detectedModel)) {
            labels = new String[]{t("Bass", "Бас"), t("Mid", "Сред."), t("Treble", "Выс.")};
            maxDb = Protocol.isBose(detectedModel) ? 10 : 6;
        } else if (Protocol.isSoundcore(detectedModel)) {
            labels = new String[]{"100", "200", "400", "800", "1.6k", "3.2k", "6.4k", "12.8k"};
            maxDb = 6;
        } else if (Protocol.isSennheiser(detectedModel) || Protocol.isPixelBuds(detectedModel)) {
            labels = new String[]{"63", "250", "1k", "4k", "8k"};
            maxDb = 6;
        } else {
            labels = new String[]{"62", "250", "1k", "4k", "8k", "16k"};
            maxDb = 6;
        }
        final int n = labels.length;
        final int[] vals = new int[n];
        for (int i = 0; i < n; i++) vals[i] = Math.max(-maxDb, Math.min(maxDb, customEqGains[i]));

        ScrollView sv = new ScrollView(this);
        LinearLayout box = vbox();
        box.setPadding(dp(18), dp(10), dp(18), dp(8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            LinearLayout row = hbox();
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = tv(labels[i], 12, Color.WHITE, true);
            lbl.setMinWidth(dp(44));
            final TextView valTv = tv((vals[i] > 0 ? "+" : "") + vals[i] + " dB", 12, C_ACCENT, true);
            valTv.setMinWidth(dp(46));
            valTv.setGravity(Gravity.END);
            SeekBar sb = new SeekBar(this);
            sb.setMax(maxDb * 2);
            sb.setProgress(vals[i] + maxDb);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    int g = p - maxDb;
                    vals[idx] = g;
                    valTv.setText((g > 0 ? "+" : "") + g + " dB");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            row.addView(lbl);
            row.addView(sb, lpWeight(1));
            row.addView(valTv);
            box.addView(row, lpMatchWrap());
        }
        sv.addView(box);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Custom equalizer", "Настраиваемый эквалайзер"))
            .setView(sv)
            .setPositiveButton(t("Apply", "Применить"), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    System.arraycopy(vals, 0, customEqGains, 0, n);
                    link.setCustomEq(vals);
                }
            })
            .setNeutralButton(t("Reset", "Сброс"), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Arrays.fill(vals, 0);
                    Arrays.fill(customEqGains, 0);
                    link.setCustomEq(vals);
                }
            })
            .setNegativeButton(t("Cancel", "Отмена"), null)
            .show();
    }

    private void updateLogsView() {
        tvLogsHeader.setText(t("Protocol logs (" + logs.size() + ")", "Протокольный лог (" + logs.size() + ")"));
        StringBuilder sb = new StringBuilder();
        for (String l : logs) sb.append(l).append('\n');
        tvLogsBody.setText(sb.length() == 0 ? t("Logs are empty", "Логи пусты") : sb.toString());
        if (logsExpanded) {
            logsScroll.post(new Runnable() {
                @Override public void run() { logsScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    // ---------- Helpers ----------
    private View[] makeBatCard(String label) {
        LinearLayout c = card(C_SURFACE, 12);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(10), dp(12), dp(10), dp(12));
        c.addView(tv(label, 12, C_MUTED, false));
        TextView val = tv("—", 18, Color.WHITE, true);
        val.setPadding(0, dp(6), 0, dp(8));
        c.addView(val);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(roundRect(0xFF2C2C2C, 4));
        View fill = new View(this);
        fill.setBackground(roundRect(C_GREEN, 4));
        track.addView(fill, new FrameLayout.LayoutParams(0, dp(4)));
        c.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        return new View[]{c, val, fill};
    }

    private void updateBat(TextView tvVal, final View bar, final int lvl, boolean chg) {
        tvVal.setText(lvl >= 0 ? (lvl + "%" + (chg ? " ⚡" : "")) : "—");
        int col = chg ? C_ACCENT : (lvl >= 0 && lvl <= 19 ? C_RED : C_GREEN);
        bar.setBackground(roundRect(col, 4));
        bar.post(new Runnable() {
            @Override public void run() {
                View parent = (View) bar.getParent();
                if (parent == null) return;
                int w = lvl >= 0 ? (int) (parent.getWidth() * Math.min(100, lvl) / 100f) : 0;
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                if (lp.width != w) { lp.width = w; bar.setLayoutParams(lp); }
            }
        });
    }

    private Object[] makeSwitchCard(String title, String desc, final byte featId) {
        LinearLayout c = card(C_SURFACE, 16);
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = vbox();
        left.addView(tv(title, 15, Color.WHITE, true));
        TextView tvD = tv(desc, 12, C_MUTED, false);
        tvD.setPadding(0, dp(4), dp(10), 0);
        left.addView(tvD);
        row.addView(left, lpWeight(1));

        Switch sw = new Switch(this);
        sw.setThumbTintList(new ColorStateList(
            new int[][]{{android.R.attr.state_checked}, {}},
            new int[]{Color.BLACK, 0xFFBBBBBB}));
        sw.setTrackTintList(new ColorStateList(
            new int[][]{{android.R.attr.state_checked}, {}},
            new int[]{C_ACCENT, 0xFF444444}));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (!updatingUi && connState == BudsLink.CONN_CONNECTED) {
                    link.setSwitch(featId, isChecked);
                }
            }
        });
        row.addView(sw);
        c.addView(row, lpMatchWrap());
        return new Object[]{c, sw, tvD};
    }

    private void setSwitchUi(Switch sw, Boolean val, boolean enabled) {
        sw.setEnabled(enabled);
        sw.setChecked(Boolean.TRUE.equals(val));
    }

    private TextView makeUseBadge(LinearLayout row, String badge) {
        TextView b = tv(badge, 12, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundRect(C_SURFACE2, 12));
        row.addView(b, new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.addView(spaceW(8));
        TextView val = tv("—", 14, Color.WHITE, false);
        row.addView(val, lpWeight(1));
        return val;
    }

    private boolean tryOsSetAlias(BluetoothDevice dev, String alias) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                return dev.setAlias(alias) == 0;
            }
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("setAlias", String.class);
            Object r = m.invoke(dev, alias);
            return Boolean.TRUE.equals(r) || Integer.valueOf(0).equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestCdmAssociateForRename(final BluetoothDevice dev, final String nm) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            android.companion.CompanionDeviceManager cdm = (android.companion.CompanionDeviceManager) getSystemService(COMPANION_DEVICE_SERVICE);
            if (cdm == null) return;
            pendingCdmRename = nm;
            android.companion.BluetoothDeviceFilter filter = new android.companion.BluetoothDeviceFilter.Builder()
                .setAddress(dev.getAddress())
                .build();
            android.companion.AssociationRequest req = new android.companion.AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build();
            cdm.associate(req, new android.companion.CompanionDeviceManager.Callback() {
                @Override public void onDeviceFound(IntentSender chooserLauncher) {
                    try {
                        startIntentSenderForResult(chooserLauncher, REQ_CDM_RENAME, null, 0, 0, 0);
                    } catch (Throwable ignored) {}
                }
                @Override public void onFailure(CharSequence error) {
                    pendingCdmRename = null;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, t("Display name saved in realness", "Имя сохранено в realness"), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }, null);
        } catch (Throwable ignored) {
            Toast.makeText(this, t("Display name saved in realness", "Имя сохранено в realness"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int reqCode, int resCode, Intent data) {
        super.onActivityResult(reqCode, resCode, data);
        if (reqCode == REQ_CDM_RENAME && resCode == RESULT_OK && pendingCdmRename != null && detectedDevice != null) {
            String nm = pendingCdmRename;
            pendingCdmRename = null;
            boolean ok = tryOsSetAlias(detectedDevice, nm);
            Toast.makeText(this, ok
                ? t("Bluetooth name updated in Android", "Имя Bluetooth обновлено в Android")
                : t("Display name saved in realness", "Имя сохранено в realness"), Toast.LENGTH_SHORT).show();
        }
    }

    private String fmtAgo(long tsMs) {
        long sec = Math.max(0L, (System.currentTimeMillis() - tsMs) / 1000L);
        if (sec < 60L) return t("now", "сейчас");
        if (sec < 3600L) return (sec / 60L) + t("m", "м");
        if (sec < 86400L) return (sec / 3600L) + t("h", "ч");
        return (sec / 86400L) + t("d", "д");
    }

    private String fmtMinutes(int min) {
        if (min < 0) return "—";
        int h = min / 60, m = min % 60;
        if (h > 0) return ru ? (h + " ч " + m + " мин") : (h + "h " + m + "m");
        return ru ? (m + " мин") : (m + "m");
    }

    private String actionLabel(int fn) {
        switch (fn) {
            case 0x01: return t("Play / Pause", "Воспроизведение / Пауза");
            case 0x06: return t("Next track", "Следующая композиция");
            case 0x05: return t("Previous track", "Предыдущая композиция");
            case 0x03: return t("Voice assistant", "Голосовой помощник");
            case 0x08: return t("Noise cancellation", "Шумоподавление");
            case 0x0D: return t("Switch devices", "Переключить устройства");
            case 0x11: return t("Game mode", "Игровой режим");
            case 0x0B: return t("Volume up", "Увеличение громкости");
            case 0x0C: return t("Volume down", "Уменьшение громкости");
            default: return t("None", "Нет действия");
        }
    }

    private String t(String en, String r) { return ru ? r : en; }

    private TextView addSection(LinearLayout parent, String title) {
        TextView tv = tv(title, 15, Color.WHITE, true);
        tv.setPadding(dp(2), 0, 0, dp(8));
        parent.addView(tv, lpMatchWrap());
        return tv;
    }

    private void addSpaced(LinearLayout parent, View v, int bottomDp) {
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.bottomMargin = dp(bottomDp);
        parent.addView(v, lp);
    }

    private LinearLayout card(int bg, int radDp) {
        LinearLayout l = vbox();
        l.setBackground(roundRect(bg, radDp));
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        return l;
    }

    private TextView segBtn(String txt, int sp) {
        TextView b = tv(txt, sp, Color.WHITE, false);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(4), dp(11), dp(4), dp(11));
        return b;
    }

    private void styleSeg(TextView b, boolean sel, boolean enabled, int unselectedBg) {
        b.setBackground(roundRect(sel ? C_ACCENT : unselectedBg, 10));
        b.setTextColor(sel ? Color.BLACK : (enabled ? Color.WHITE : C_MUTED));
        b.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
    }

    private TextView pillBtn(String txt, int bg, int fg) {
        TextView b = tv(txt, 13, fg, true);
        b.setBackground(roundRect(bg, 20));
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        return b;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable roundRect(int color, int radDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radDp));
        return d;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private View spaceW(int d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(d), 1));
        return v;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWeight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private int dp(int v) { return Math.round(v * density); }
}
