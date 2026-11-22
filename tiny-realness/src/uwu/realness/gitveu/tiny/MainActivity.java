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

    private int sysStatus = ST_DISCONNECTED;
    private int connState = BudsLink.CONN_DISCONNECTED;
    private int detectedModel = Protocol.MODEL_UNKNOWN;
    private BluetoothDevice detectedDevice;
    private String unsupportedName;
    private Protocol.State budsState = new Protocol.State();
    private final LinkedList<String> logs = new LinkedList<>();
    private int selectedSide = 0; // 0=L, 1=R
    private boolean logsExpanded = false;
    private boolean updatingUi = false;

    // UI references
    private TextView tvTopTitle, btnRefresh;
    private View cardPerm, cardUnsupported;
    private TextView tvUnsupportedMsg;
    private TextView tvDevName, tvDevSub, tvBadge, tvHint, btnReconnect;
    private TextView tvBatL, tvBatC, tvBatR;
    private View barBatL, barBatC, barBatR;
    private final TextView[] btnAnc = new TextView[3];
    private final TextView[] btnEq = new TextView[4];
    private TextView tvCustomEq;
    private final TextView[] btnSide = new TextView[2];
    private final TextView[] tvGestVal = new TextView[3];
    private final TextView[] btnSpatial = new TextView[2];

    private View cardHq, cardDynamic, cardDual, cardAutoPlay, cardAutoAns, cardUseTime;
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
                if (d != null && Protocol.detectModel(d.getName()) != Protocol.MODEL_UNKNOWN) {
                    detectedDevice = d;
                    detectedModel = Protocol.detectModel(d.getName());
                    unsupportedName = null;
                    sysStatus = ST_CONNECTED_OS;
                    link.connect(d);
                    render();
                    return;
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && Protocol.detectModel(d.getName()) != Protocol.MODEL_UNKNOWN) {
                    sysStatus = ST_DISCONNECTED;
                    link.disconnect();
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

    private void checkAndAutoConnect() {
        if (!hasPerms()) { sysStatus = ST_NO_PERM; render(); return; }
        try {
            List<BluetoothDevice> a2dpDevs = a2dp != null ? a2dp.getConnectedDevices() : Collections.<BluetoothDevice>emptyList();
            Set<BluetoothDevice> bonded = adapter != null ? adapter.getBondedDevices() : Collections.<BluetoothDevice>emptySet();

            for (BluetoothDevice d : a2dpDevs) {
                int m = Protocol.detectModel(d.getName());
                if (m != Protocol.MODEL_UNKNOWN) {
                    detectedDevice = d;
                    detectedModel = m;
                    unsupportedName = null;
                    sysStatus = (connState == BudsLink.CONN_CONNECTED) ? ST_ACTIVE : ST_CONNECTED_OS;
                    if (connState == BudsLink.CONN_DISCONNECTED) link.connect(d);
                    render();
                    return;
                }
            }
            if (connState != BudsLink.CONN_DISCONNECTED) link.disconnect();
            if (!a2dpDevs.isEmpty()) {
                BluetoothDevice u = a2dpDevs.get(0);
                detectedDevice = null;
                detectedModel = Protocol.MODEL_UNKNOWN;
                unsupportedName = u.getName() != null ? u.getName() : u.getAddress();
                sysStatus = ST_UNSUPPORTED;
                render();
                return;
            }
            for (BluetoothDevice d : bonded) {
                int m = Protocol.detectModel(d.getName());
                if (m != Protocol.MODEL_UNKNOWN) {
                    detectedDevice = d;
                    detectedModel = m;
                    unsupportedName = null;
                    sysStatus = ST_DISCONNECTED;
                    render();
                    return;
                }
            }
            detectedDevice = null;
            detectedModel = Protocol.MODEL_UNKNOWN;
            unsupportedName = null;
            sysStatus = ST_NO_PAIRED;
        } catch (Exception ignored) {}
        render();
    }

    @Override
    public void onStateChanged(int cs, Protocol.State st) {
        connState = cs;
        budsState = st;
        if (cs == BudsLink.CONN_CONNECTED) sysStatus = ST_ACTIVE;
        else if (sysStatus == ST_ACTIVE) sysStatus = ST_CONNECTED_OS;
        render();
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
        topBar.setPadding(dp(16), dp(14), dp(12), dp(14));

        tvTopTitle = tv("realness tiny", 20, Color.WHITE, true);
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
            @Override public void onClick(View v) { if (detectedDevice != null) link.connect(detectedDevice); }
        });
        stCard.addView(btnReconnect, rlp);
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
        addSection(content, t("Noise cancellation", "Шумоподавление"));
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
        addSpaced(content, ancCard, 16);

        // 6. EQ Section
        addSection(content, t("Sound effects", "Звуковые эффекты"));
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
        addSpaced(content, eqCard, 16);

        // 7. Button Settings
        addSection(content, t("Button settings", "Настройка кнопок"));
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
            keyCard.addView(row, lpMatchWrap());
            if (g < 2) {
                View div = new View(this);
                div.setBackgroundColor(C_SURFACE2);
                keyCard.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        addSpaced(content, keyCard, 16);

        // 8. Spatial Audio
        addSection(content, t("Spatial audio", "Пространственное аудио"));
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
        addSpaced(content, spCard, 14);

        // 9. High-Definition Audio (LDAC / LHDC 5.0)
        Object[] hq = makeSwitchCard(
            t("High-definition audio", "Звук высокой чёткости"),
            t("LDAC codec for superior audio quality.", "Кодек LDAC, обеспечивающий превосходное качество звука."),
            Protocol.FEAT_HQ_AUDIO);
        cardHq = (View) hq[0]; swHq = (Switch) hq[1]; tvHqDesc = (TextView) hq[2];
        addSpaced(content, cardHq, 12);

        // 10. Dynamic Audio
        Object[] da = makeSwitchCard(
            t("Dynamic audio", "Динамическое аудио"),
            t("Dynamically enhances bass and sound depth.", "Динамически усиливает басы и глубину звучания."),
            Protocol.FEAT_DYNAMIC);
        cardDynamic = (View) da[0]; swDynamic = (Switch) da[1];
        addSpaced(content, cardDynamic, 16);

        // 11. Other Section
        addSection(content, t("Other", "Другое"));

        // Game Mode
        Object[] gm = makeSwitchCard(
            t("Game mode", "Игровой режим"),
            t("Provides smooth gameplay with ultra-low audio latency.", "Обеспечивает плавный игровой процесс с минимальной задержкой звука."),
            Protocol.FEAT_GAME_MODE);
        swGame = (Switch) gm[1];
        addSpaced(content, (View) gm[0], 12);

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

        tvTopTitle.setText(detectedDevice != null && detectedDevice.getName() != null
            ? detectedDevice.getName() : "realness tiny");
        btnRefresh.setVisibility(connected ? View.VISIBLE : View.GONE);

        cardPerm.setVisibility(hasPerms() ? View.GONE : View.VISIBLE);
        boolean showUnsup = (sysStatus == ST_UNSUPPORTED && unsupportedName != null);
        cardUnsupported.setVisibility(showUnsup ? View.VISIBLE : View.GONE);
        if (showUnsup) {
            tvUnsupportedMsg.setText(t(
                "\"" + unsupportedName + "\" is not supported. Supported: realme Buds T300, T200, Air 7 Pro.",
                "«" + unsupportedName + "» не поддерживается. Поддерживаются: realme Buds T300, T200, Air 7 Pro."));
        }

        // Status Card
        String name = detectedDevice != null && detectedDevice.getName() != null
            ? detectedDevice.getName() : (unsupportedName != null ? unsupportedName : "realme Buds");
        tvDevName.setText(name);
        tvDevSub.setText(detectedDevice != null ? detectedDevice.getAddress()
            : (sysStatus == ST_UNSUPPORTED ? t("Unsupported device", "Устройство не поддерживается")
            : t("Searching for paired device…", "Поиск сопряжённого устройства…")));

        String badgeTxt; int badgeCol;
        if (connected) { badgeTxt = t("Connected", "Подключено"); badgeCol = C_GREEN; }
        else if (connState == BudsLink.CONN_CONNECTING) { badgeTxt = t("Connecting…", "Подключение…"); badgeCol = C_ORANGE; }
        else if (sysStatus == ST_CONNECTED_OS) { badgeTxt = t("Online (connecting)", "В сети (подключение)"); badgeCol = C_ORANGE; }
        else if (sysStatus == ST_UNSUPPORTED) { badgeTxt = t("Unsupported", "Не поддерживается"); badgeCol = C_RED; }
        else if (sysStatus == ST_NO_PAIRED) { badgeTxt = t("Not paired", "Не сопряжено"); badgeCol = C_RED; }
        else if (sysStatus == ST_NO_PERM) { badgeTxt = t("No permissions", "Нет прав"); badgeCol = C_RED; }
        else { badgeTxt = t("In case / disconnected", "В кейсе / отключено"); badgeCol = C_MUTED; }
        tvBadge.setText(badgeTxt);
        tvBadge.setTextColor(badgeCol);
        tvBadge.setBackground(roundRect((badgeCol & 0x00FFFFFF) | 0x33000000, 12));

        String hint = null;
        if (connected) hint = null;
        else if (connState == BudsLink.CONN_CONNECTING) hint = t("Connecting via SPP/GATT…", "Установка соединения через SPP/GATT…");
        else if (sysStatus == ST_UNSUPPORTED) hint = t("Only realme Buds T300, T200, Air 7 Pro are supported.", "Поддерживаются только realme Buds T300, T200, Air 7 Pro.");
        else if (sysStatus == ST_DISCONNECTED) hint = t("Open the case or put on the earbuds.", "Откройте кейс или наденьте наушники.");
        else if (sysStatus == ST_NO_PAIRED) hint = t("No supported realme Buds (T300, T200, Air 7 Pro) paired.", "Поддерживаемые realme Buds (T300, T200, Air 7 Pro) не найдены.");
        else if (sysStatus == ST_NO_PERM) hint = t("Grant Bluetooth permissions above.", "Предоставьте доступ к Bluetooth выше.");
        tvHint.setVisibility(hint != null ? View.VISIBLE : View.GONE);
        if (hint != null) tvHint.setText(hint);

        btnReconnect.setVisibility((detectedDevice != null && !connected && sysStatus == ST_CONNECTED_OS) ? View.VISIBLE : View.GONE);

        // Batteries
        updateBat(tvBatL, barBatL, budsState.leftBat, budsState.leftChg);
        updateBat(tvBatC, barBatC, budsState.caseBat, budsState.caseChg);
        updateBat(tvBatR, barBatR, budsState.rightBat, budsState.rightChg);

        // ANC
        int[] ancVals = {Protocol.ANC_ON, Protocol.ANC_OFF, Protocol.ANC_TRANSPARENCY};
        for (int i = 0; i < 3; i++) {
            styleSeg(btnAnc[i], budsState.ancMode == ancVals[i], connected, Color.TRANSPARENT);
        }

        // EQ
        int[] eqIds = {2, 0, 3, 1};
        for (int i = 0; i < 4; i++) {
            boolean sel = (budsState.eqId == eqIds[i]) && budsState.customEqName == null;
            styleSeg(btnEq[i], sel, connected, C_SURFACE2);
        }
        tvCustomEq.setVisibility(budsState.customEqName != null ? View.VISIBLE : View.GONE);
        if (budsState.customEqName != null) {
            tvCustomEq.setText(t("Custom: " + budsState.customEqName + " (active)",
                "Пользовательский: " + budsState.customEqName + " (активен)"));
        }

        // Touch settings
        for (int i = 0; i < 2; i++) styleSeg(btnSide[i], selectedSide == i, connected, Color.TRANSPARENT);
        for (int g = 0; g < 3; g++) {
            int fn = budsState.touch[selectedSide][g];
            tvGestVal[g].setText(fn >= 0 ? actionLabel(fn) : "—");
            tvGestVal[g].setTextColor(connected ? C_ACCENT : C_MUTED);
        }

        // Spatial
        styleSeg(btnSpatial[0], Boolean.FALSE.equals(budsState.spatialAudio), connected, Color.TRANSPARENT);
        styleSeg(btnSpatial[1], Boolean.TRUE.equals(budsState.spatialAudio), connected, Color.TRANSPARENT);

        // Model-gated cards
        cardHq.setVisibility(Protocol.supportsHqAudio(detectedModel) ? View.VISIBLE : View.GONE);
        if (Protocol.supportsHqAudio(detectedModel)) {
            tvHqDesc.setText(Protocol.usesLhdc(detectedModel)
                ? t("Play high-resolution LHDC 5.0 audio.", "Воспроизводите аудио в высоком разрешении LHDC 5.0.")
                : t("LDAC codec for superior audio quality.", "Кодек LDAC, обеспечивающий превосходное качество звука."));
        }
        setSwitchUi(swHq, budsState.hqAudio, connected);

        cardDynamic.setVisibility(Protocol.supportsDynamic(detectedModel) ? View.VISIBLE : View.GONE);
        setSwitchUi(swDynamic, budsState.dynamicAudio, connected);

        setSwitchUi(swGame, budsState.gameMode, connected);

        cardDual.setVisibility(Protocol.supportsDual(detectedModel) ? View.VISIBLE : View.GONE);
        setSwitchUi(swDual, budsState.dualConn, connected);
        renderMultiDevices();

        cardAutoPlay.setVisibility(Protocol.supportsAutoPlayPause(detectedModel) ? View.VISIBLE : View.GONE);
        setSwitchUi(swAutoPlay, budsState.autoPlayPause, connected);

        cardAutoAns.setVisibility(Protocol.supportsAutoAnswer(detectedModel) ? View.VISIBLE : View.GONE);
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
        final byte[] allowed = gest == 0 ? Protocol.ACTIONS_DOUBLE
            : (gest == 1 ? Protocol.ACTIONS_TRIPLE : Protocol.ACTIONS_HOLD);
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

    private void addSection(LinearLayout parent, String title) {
        TextView tv = tv(title, 15, Color.WHITE, true);
        tv.setPadding(dp(2), 0, 0, dp(8));
        parent.addView(tv, lpMatchWrap());
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
