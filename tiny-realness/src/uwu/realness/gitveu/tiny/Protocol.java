package uwu.realness.gitveu.tiny;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Protocol {
    static final byte OPPO_SOF = (byte) 0xAA;

    static final int CMD_BATTERY_REQ = 0x0106;
    static final int CMD_BATTERY_RSP = 0x8106;
    static final int CMD_KEY_REQ = 0x0108;
    static final int CMD_KEY_RSP = 0x8108;
    static final int CMD_ANC_STATE_REQ = 0x010C;
    static final int CMD_ANC_STATE_RSP = 0x810C;
    static final int CMD_SWITCH_REQ = 0x010D;
    static final int CMD_SWITCH_RSP = 0x810D;
    static final int CMD_EQ_REQ = 0x010F;
    static final int CMD_EQ_RSP = 0x810F;
    static final int CMD_MULTI_REQ = 0x0112;
    static final int CMD_MULTI_RSP = 0x8112;
    static final int CMD_ALL_EQ_REQ = 0x0122;
    static final int CMD_ALL_EQ_RSP = 0x8122;
    static final int CMD_USE_TIME_REQ = 0x0128;
    static final int CMD_USE_TIME_RSP = 0x8128;

    static final int CMD_NOTIFY_REG = 0x0201;
    static final int CMD_NOTIFY_REG_RSP = 0x8201;
    static final int CMD_EVENT = 0x0204;

    static final int CMD_KEY_SET = 0x0401;
    static final int CMD_KEY_SET_RSP = 0x8401;
    static final int CMD_SWITCH_SET = 0x0403;
    static final int CMD_SWITCH_SET_RSP = 0x8403;
    static final int CMD_ANC_SET = 0x0404;
    static final int CMD_ANC_RSP = 0x8404;
    static final int CMD_EQ_SET = 0x0406;
    static final int CMD_EQ_SET_RSP = 0x8406;
    static final int CMD_NOTIFICATION = 0x8400;

    static final byte FEAT_AUTO_PLAY_PAUSE = 0x04;
    static final byte FEAT_GAME_MODE = 0x06;
    static final byte FEAT_AUTO_ANSWER = 0x08;
    static final byte FEAT_DUAL_CONN = 0x11;
    static final byte FEAT_HQ_AUDIO = 0x18;
    static final byte FEAT_SPATIAL = 0x1B;
    static final byte FEAT_DYNAMIC = 0x1D;

    static final byte ANC_OFF = 0x01;
    static final byte ANC_TRANSPARENCY = 0x02;
    static final byte ANC_ON = 0x08;

    static final int MODEL_UNKNOWN = 0;
    // realme / OnePlus / OPPO / DIZO (TL + OPPOv1): 1..29
    static final int MODEL_T300 = 1;
    static final int MODEL_T200 = 2;
    static final int MODEL_AIR_7_PRO = 3;
    static final int MODEL_AIR_8_PRO = 10;   // realme Buds Air 8 / Air 8 Pro
    static final int MODEL_REALME_ADV = 4;   // Air 5/6/7/8, T310, T500 Pro, N1 Pro
    static final int MODEL_REALME_GEN = 5;   // realme Buds / T110 / T200x / Clip / Wireless / DIZO
    static final int MODEL_ONEPLUS_PRO = 6;  // OnePlus Buds 3 / 4 / Pro 2 / Pro 3 / Pro 4 / Nord Buds 4 Pro
    static final int MODEL_ONEPLUS_GEN = 7;  // OnePlus Buds / Ace / Nord / Z / Flow / Bullets
    static final int MODEL_OPPO_PRO = 8;     // OPPO Enco X / X2 / X3 / X4 / Air3 / Air4 / Air5 / Free4
    static final int MODEL_OPPO_GEN = 9;     // OPPO Enco Air / Buds / Free / Clip / R / W / M

    // HUAWEI / HONOR (Huawei SPP — OpenFreebuds): 30..49
    static final int MODEL_HW_3 = 30;
    static final int MODEL_HW_4I = 31;
    static final int MODEL_HW_5I = 32;
    static final int MODEL_HW_6I = 33;
    static final int MODEL_HW_PRO = 34;
    static final int MODEL_HW_PRO2 = 35;
    static final int MODEL_HW_PRO3 = 36;
    static final int MODEL_HW_PRO5 = 37;
    static final int MODEL_HW_CLIP = 38;
    static final int MODEL_HW_CLIP2 = 39;
    static final int MODEL_HW_SE = 40;
    static final int MODEL_HW_SE2 = 41;
    static final int MODEL_HW_SE4 = 42;
    static final int MODEL_HW_STUDIO = 43;
    static final int MODEL_HW_LACE = 44;
    static final int MODEL_HW_LACE2 = 45;
    static final int MODEL_HONOR_S2 = 46;
    static final int MODEL_HW_GENERIC = 47;

    // HONOR CHOICE (Honor Choice SPP — noctalia/honor-earbuds): 50..59
    static final int MODEL_HONOR_X8 = 50;
    static final int MODEL_HONOR_X5 = 51;
    static final int MODEL_HONOR_X3 = 52;
    static final int MODEL_HONOR_X2 = 53;
    static final int MODEL_HONOR_CHOICE_GEN = 54;

    // Nothing / CMF (Nothing SPP — swift-nothing-ear all 23 models): 60..69
    static final int MODEL_NOTHING_EAR = 60;      // Ear (1)/(2)/(a)/(3)/(3a)/Ear/Headphone (1)/(1) Pro/(a)
    static final int MODEL_NOTHING_OPEN = 61;     // Ear (stick) / Ear (open) / CMF Clip Pro / Speaker
    static final int MODEL_CMF_CLASSIC = 62;      // CMF Buds Pro / Neckband Pro
    static final int MODEL_CMF_LISTENING = 63;    // CMF Buds / Buds 2 / 2a / 2 Plus / Buds Pro 2 / Headphone Pro
    static final int MODEL_CMF_NEO = 64;          // CMF Buds Neo

    // Samsung Galaxy Buds (Galaxy Buds SPP): 70..79
    static final int MODEL_GALAXY_BUDS = 70;      // Buds (2019, legacy 0xFE)
    static final int MODEL_GALAXY_PLUS = 71;      // Buds+
    static final int MODEL_GALAXY_LIVE = 72;      // Buds Live
    static final int MODEL_GALAXY_MODERN = 73;    // Buds Pro / Buds2 / 2 Pro / FE / Buds3 / 3 Pro / Core / Buds4

    // kde-headphones extra protocols: 80..89
    static final int MODEL_SONY_MDR = 80;         // Sony WH / WF / LinkBuds / ULT (MDR V1/V2)
    static final int MODEL_BOSE_BMAP = 81;        // Bose QuietComfort / NC 700 / Sport (BMAP)
    static final int MODEL_SOUNDCORE = 82;        // Anker Soundcore Liberty / Space / Life / Q / P / A
    static final int MODEL_XIAOMI = 83;           // Xiaomi / Redmi / POCO Buds (SAFER+ SPP)
    static final int MODEL_SENNHEISER = 84;       // Sennheiser MOMENTUM / ACCENTUM / CX (GAIA v3)
    static final int MODEL_JABRA = 85;            // Jabra Elite / Evolve / Talk (Jabra SPP)
    static final int MODEL_PIXEL_BUDS = 86;       // Google Pixel Buds Pro / Pro 2 / A-Series / 2a (Maestro)
    static final int MODEL_JBL = 87;              // JBL Tour / Live / Tune / Wave / Reflect / Club (BLE GATT)

    static final int[] PICKER_MODELS = {
        MODEL_UNKNOWN,
        MODEL_T300, MODEL_T200, MODEL_AIR_7_PRO, MODEL_AIR_8_PRO, MODEL_REALME_ADV, MODEL_REALME_GEN,
        MODEL_ONEPLUS_PRO, MODEL_ONEPLUS_GEN,
        MODEL_OPPO_PRO, MODEL_OPPO_GEN,
        MODEL_HW_PRO3, MODEL_HW_PRO2, MODEL_HW_PRO, MODEL_HW_PRO5, MODEL_HW_6I, MODEL_HW_5I, MODEL_HW_4I,
        MODEL_HW_CLIP, MODEL_HW_CLIP2, MODEL_HW_SE4, MODEL_HW_SE2, MODEL_HW_SE, MODEL_HW_3,
        MODEL_HW_STUDIO, MODEL_HW_LACE, MODEL_HW_LACE2, MODEL_HONOR_S2, MODEL_HW_GENERIC,
        MODEL_HONOR_X8, MODEL_HONOR_X5, MODEL_HONOR_X3, MODEL_HONOR_X2, MODEL_HONOR_CHOICE_GEN,
        MODEL_NOTHING_EAR, MODEL_NOTHING_OPEN, MODEL_CMF_CLASSIC, MODEL_CMF_LISTENING, MODEL_CMF_NEO,
        MODEL_GALAXY_MODERN, MODEL_GALAXY_LIVE, MODEL_GALAXY_PLUS, MODEL_GALAXY_BUDS,
        MODEL_SONY_MDR, MODEL_BOSE_BMAP, MODEL_SOUNDCORE, MODEL_XIAOMI,
        MODEL_SENNHEISER, MODEL_JABRA, MODEL_PIXEL_BUDS, MODEL_JBL
    };

    static String modelTitle(int m) {
        switch (m) {
            case MODEL_T300: return "realme Buds T300";
            case MODEL_T200: return "realme Buds T200";
            case MODEL_AIR_7_PRO: return "realme Buds Air 7 Pro";
            case MODEL_AIR_8_PRO: return "realme Buds Air 8 Pro";
            case MODEL_REALME_ADV: return "realme Buds Air 5/6/7/8 / T310 / T500";
            case MODEL_REALME_GEN: return "realme Buds / DIZO (Generic)";
            case MODEL_ONEPLUS_PRO: return "OnePlus Buds 3 / 4 / Pro 2 / 3 / 4";
            case MODEL_ONEPLUS_GEN: return "OnePlus Buds / Ace / Nord / Z";
            case MODEL_OPPO_PRO: return "OPPO Enco X / X2 / X3 / X4 / Air3..5";
            case MODEL_OPPO_GEN: return "OPPO Enco Air / Buds / Free / Clip";
            case MODEL_HW_3: return "HUAWEI FreeBuds 3";
            case MODEL_HW_4I: return "HUAWEI FreeBuds 4i";
            case MODEL_HW_5I: return "HUAWEI FreeBuds 5i";
            case MODEL_HW_6I: return "HUAWEI FreeBuds 6i";
            case MODEL_HW_PRO: return "HUAWEI FreeBuds Pro";
            case MODEL_HW_PRO2: return "HUAWEI FreeBuds Pro 2";
            case MODEL_HW_PRO3: return "HUAWEI FreeBuds Pro 3 / 4";
            case MODEL_HW_PRO5: return "HUAWEI FreeBuds Pro 5";
            case MODEL_HW_CLIP: return "HUAWEI FreeClip";
            case MODEL_HW_CLIP2: return "HUAWEI FreeClip 2";
            case MODEL_HW_SE: return "HUAWEI FreeBuds SE";
            case MODEL_HW_SE2: return "HUAWEI FreeBuds SE 2";
            case MODEL_HW_SE4: return "HUAWEI FreeBuds SE 4";
            case MODEL_HW_STUDIO: return "HUAWEI FreeBuds Studio";
            case MODEL_HW_LACE: return "HUAWEI FreeLace Pro";
            case MODEL_HW_LACE2: return "HUAWEI FreeLace Pro 2";
            case MODEL_HONOR_S2: return "HONOR Earbuds S2";
            case MODEL_HW_GENERIC: return "HUAWEI FreeBuds (Generic)";
            case MODEL_HONOR_X8: return "HONOR CHOICE Earbuds X8";
            case MODEL_HONOR_X5: return "HONOR CHOICE Earbuds X5 / X5 Pro";
            case MODEL_HONOR_X3: return "HONOR CHOICE Earbuds X3 / Lite";
            case MODEL_HONOR_X2: return "HONOR CHOICE Earbuds X2 / X";
            case MODEL_HONOR_CHOICE_GEN: return "HONOR CHOICE (Generic)";
            case MODEL_NOTHING_EAR: return "Nothing Ear / (1)/(2)/(a)/(3)/(3a) / Headphone";
            case MODEL_NOTHING_OPEN: return "Nothing Ear (stick)/(open) / CMF Clip Pro";
            case MODEL_CMF_CLASSIC: return "CMF Buds Pro / Neckband Pro";
            case MODEL_CMF_LISTENING: return "CMF Buds / Buds 2 / Buds Pro 2";
            case MODEL_CMF_NEO: return "CMF Buds Neo";
            case MODEL_GALAXY_MODERN: return "Galaxy Buds Pro / 2 / FE / 3 / 4";
            case MODEL_GALAXY_LIVE: return "Galaxy Buds Live";
            case MODEL_GALAXY_PLUS: return "Galaxy Buds+";
            case MODEL_GALAXY_BUDS: return "Galaxy Buds (2019)";
            case MODEL_SONY_MDR: return "Sony WH / WF / LinkBuds / ULT";
            case MODEL_BOSE_BMAP: return "Bose QuietComfort / NC 700 / Sport";
            case MODEL_SOUNDCORE: return "Soundcore Liberty / Space / Life / Q / P";
            case MODEL_XIAOMI: return "Xiaomi / Redmi / POCO Buds";
            case MODEL_SENNHEISER: return "Sennheiser MOMENTUM / ACCENTUM / CX";
            case MODEL_JABRA: return "Jabra Elite / Evolve";
            case MODEL_PIXEL_BUDS: return "Google Pixel Buds Pro / A-Series / 2a";
            case MODEL_JBL: return "JBL Tour / Live / Tune / Wave / Reflect";
            default: return "Automatic";
        }
    }

    static final byte[] KEY_REQ_PAYLOAD = {
        0x0E, 0x01, 0x03, 0x04, 0x05, 0x06, 0x0B, 0x0C, 0x08, 0x0D, 0x11, 0x1F, 0x1A, 0x1B, 0x00
    };

    static final byte[] ACTIONS_DOUBLE = {0x01, 0x06, 0x03, 0x00};
    static final byte[] ACTIONS_TRIPLE = {0x05, 0x06, 0x0B, 0x0C, 0x0D, 0x03, 0x11, 0x00};
    static final byte[] ACTIONS_HOLD = {0x08, 0x03, 0x11, 0x00};

    static final byte[] HW_ACTIONS_TAP = {0x01, 0x06, 0x05, 0x03, 0x00};
    static final byte[] HW_ACTIONS_FB3 = {0x01, 0x06, 0x08, 0x03, 0x00};
    static final byte[] HW_ACTIONS_HOLD = {0x08, 0x00};
    static final byte[] HW_ACTIONS_HOLD_EXTRA = {0x08, 0x03, 0x0B, 0x0C, 0x00};

    static int detectModel(String name) {
        if (name == null) return MODEL_UNKNOWN;
        String n = name.toLowerCase().replaceAll("\\s+", " ").trim();

        // 1. Samsung Galaxy Buds
        if (n.contains("galaxy buds") || n.contains("buds live") || n.contains("buds fe")
            || n.contains("buds core") || n.contains("buds2") || n.contains("buds3") || n.contains("buds4")) {
            if (n.contains("buds live")) return MODEL_GALAXY_LIVE;
            if (n.contains("buds+") || n.contains("buds plus")) return MODEL_GALAXY_PLUS;
            if (n.contains("buds pro") || n.contains("buds2") || n.contains("buds 2")
                || n.contains("buds fe") || n.contains("buds3") || n.contains("buds 3")
                || n.contains("buds core") || n.contains("buds4") || n.contains("buds 4")) return MODEL_GALAXY_MODERN;
            return MODEL_GALAXY_BUDS;
        }

        // 2. Nothing / CMF (all 23 models from swift-nothing-ear)
        if (n.contains("nothing") || n.contains("cmf") || n.startsWith("ear (") || n.equals("ear")
            || n.startsWith("headphone (")) {
            if (n.contains("cmf")) {
                if (n.contains("neo")) return MODEL_CMF_NEO;
                if (n.contains("clip") || n.contains("speaker")) return MODEL_NOTHING_OPEN;
                if (n.contains("neckband") || (n.contains("pro") && !n.contains("pro 2") && !n.contains("pro2") && !n.contains("headphone"))) {
                    return MODEL_CMF_CLASSIC;
                }
                return MODEL_CMF_LISTENING;
            }
            if (n.contains("stick") || n.contains("open")) return MODEL_NOTHING_OPEN;
            return MODEL_NOTHING_EAR;
        }

        // 3. Sony MDR
        if (n.contains("wh-1000") || n.contains("wf-1000") || n.contains("wh-ch") || n.contains("wh-xb")
            || n.contains("wf-c") || n.contains("wf-sp") || n.contains("wf-xb") || n.contains("wi-1000")
            || n.contains("linkbuds") || n.contains("ult wear") || n.contains("inzone") || n.contains("sony")) {
            return MODEL_SONY_MDR;
        }

        // 4. Bose BMAP
        if (n.contains("bose") || n.contains("quietcomfort") || n.startsWith("qc") || n.contains("nc 700")
            || n.contains("nc700") || n.contains("soundsport")) {
            return MODEL_BOSE_BMAP;
        }

        // 5. Anker Soundcore
        if (n.contains("soundcore") || n.contains("liberty") || n.contains("space q") || n.contains("space a")
            || n.contains("space one") || n.contains("life q") || n.contains("life p") || n.contains("life a")
            || n.contains("life dot") || n.contains("life note") || n.contains("vr p10") || n.contains("sport x")) {
            return MODEL_SOUNDCORE;
        }

        // 6. Xiaomi / Redmi / POCO Buds
        if (n.contains("xiaomi") || n.contains("redmi buds") || n.contains("redmi airdots") || n.contains("poco buds")
            || n.contains("poco pods") || n.contains("mi true wireless") || n.contains("mi airdots")) {
            return MODEL_XIAOMI;
        }

        // 7. Sennheiser GAIA
        if (n.contains("sennheiser") || n.contains("momentum") || n.contains("accentum") || n.contains("cx plus")
            || n.contains("cx true") || n.contains("cx 400") || n.contains("sport true")) {
            return MODEL_SENNHEISER;
        }

        // 8. Jabra
        if (n.contains("jabra") || n.contains("elite ") || n.contains("evolve")) {
            return MODEL_JABRA;
        }

        // 9. Google Pixel Buds
        if (n.contains("pixel buds")) {
            return MODEL_PIXEL_BUDS;
        }

        // 10. JBL BLE GATT
        if (n.contains("jbl")) {
            return MODEL_JBL;
        }

        // 11. HUAWEI
        if (n.contains("huawei") || n.contains("freebuds") || n.contains("freeclip") || n.contains("freelace")) {
            if (n.contains("freeclip 2") || n.contains("freeclip2")) return MODEL_HW_CLIP2;
            if (n.contains("freeclip")) return MODEL_HW_CLIP;
            if (n.contains("freelace pro 2") || n.contains("freelace pro2")) return MODEL_HW_LACE2;
            if (n.contains("freelace pro")) return MODEL_HW_LACE;
            if (n.contains("studio")) return MODEL_HW_STUDIO;
            if (n.contains("pro 5") || n.contains("pro5")) return MODEL_HW_PRO5;
            if (n.contains("pro 4") || n.contains("pro4") || n.contains("pro 3") || n.contains("pro3")) return MODEL_HW_PRO3;
            if (n.contains("pro 2") || n.contains("pro2")) return MODEL_HW_PRO2;
            if (n.contains("freebuds pro")) return MODEL_HW_PRO;
            if (n.contains("6i")) return MODEL_HW_6I;
            if (n.contains("5i")) return MODEL_HW_5I;
            if (n.contains("4i")) return MODEL_HW_4I;
            if (n.contains("se 4") || n.contains("se4")) return MODEL_HW_SE4;
            if (n.contains("se 2") || n.contains("se2")) return MODEL_HW_SE2;
            if (n.contains("freebuds se")) return MODEL_HW_SE;
            if (n.contains("freebuds 3")) return MODEL_HW_3;
            return MODEL_HW_GENERIC;
        }

        // 12. HONOR (Huawei SPP & HONOR CHOICE SPP)
        if (n.contains("honor") || n.contains("荣耀") || n.contains("moecen")) {
            if (n.contains("earbuds s2")) return MODEL_HONOR_S2;
            if (n.contains("earbuds 2") && !n.contains("choice")) return MODEL_HW_4I;
            if (n.contains("x8")) return MODEL_HONOR_X8;
            if (n.contains("x5")) return MODEL_HONOR_X5;
            if (n.contains("x3")) return MODEL_HONOR_X3;
            if (n.contains("x2") || n.contains("earbuds x") || n.contains("moecen")) return MODEL_HONOR_X2;
            if (n.contains("choice") || n.contains("earbuds")) return MODEL_HONOR_CHOICE_GEN;
            return MODEL_UNKNOWN;
        }

        // 13. OnePlus Buds (all QuickBuds models)
        if (n.contains("oneplus") || n.contains("nord buds") || n.contains("bullets wireless")) {
            if (n.contains("pro 2") || n.contains("pro2") || n.contains("pro 3") || n.contains("pro3")
                || n.contains("pro 4") || n.contains("pro4") || n.contains("buds 3") || n.contains("buds3")
                || n.contains("buds 4") || n.contains("buds4") || n.contains("nord buds 4 pro")) {
                return MODEL_ONEPLUS_PRO;
            }
            return MODEL_ONEPLUS_GEN;
        }

        // 14. OPPO Enco (all QuickBuds models)
        if (n.contains("oppo") || n.contains("enco")) {
            if (n.contains("enco x") || n.contains("x2") || n.contains("x3") || n.contains("x4")
                || n.contains("air3") || n.contains("air 3") || n.contains("air4") || n.contains("air 4")
                || n.contains("air5") || n.contains("air 5") || n.contains("free4") || n.contains("free 4")) {
                return MODEL_OPPO_PRO;
            }
            return MODEL_OPPO_GEN;
        }

        // 15. realme Buds / DIZO (all QuickBuds models, incl. Air 8 Pro)
        boolean isRealmeOrBuds = n.contains("realme") || n.contains("dizo") || n.contains("buds");
        if (!isRealmeOrBuds) return MODEL_UNKNOWN;
        if (n.contains("air 8 pro") || n.contains("air8 pro")) return MODEL_AIR_8_PRO;
        if (n.contains("air 7 pro") || n.contains("air7 pro")) return MODEL_AIR_7_PRO;
        if (n.contains("t200") && !n.contains("lite") && !n.contains("t200x")) return MODEL_T200;
        if (n.contains("t300")) return MODEL_T300;
        if (n.contains("t310") || n.contains("t500") || n.contains("air 5") || n.contains("air5")
            || n.contains("air 6") || n.contains("air6") || n.contains("air 7") || n.contains("air7")
            || n.contains("air 8") || n.contains("air8") || n.contains("n1 pro")) {
            return MODEL_REALME_ADV;
        }
        if (n.contains("realme") || n.contains("dizo")) return MODEL_REALME_GEN;
        return MODEL_UNKNOWN;
    }

    static boolean isRealme(int m) { return m >= 1 && m <= 29; }
    static boolean isOppoOrOnePlus(int m) { return m >= MODEL_ONEPLUS_PRO && m <= MODEL_OPPO_GEN; }
    static boolean isHuawei(int m) { return m >= 30 && m <= 49; }
    static boolean isHonorChoice(int m) { return m >= 50 && m <= 59; }
    static boolean isNothing(int m) { return m >= 60 && m <= 69; }
    static boolean isNothingListeningEq(int m) { return m == MODEL_CMF_LISTENING || m == MODEL_CMF_NEO; }
    static boolean isGalaxy(int m) { return m >= 70 && m <= 79; }
    static boolean isSony(int m) { return m == MODEL_SONY_MDR; }
    static boolean isBose(int m) { return m == MODEL_BOSE_BMAP; }
    static boolean isSoundcore(int m) { return m == MODEL_SOUNDCORE; }
    static boolean isXiaomi(int m) { return m == MODEL_XIAOMI; }
    static boolean isSennheiser(int m) { return m == MODEL_SENNHEISER; }
    static boolean isJabra(int m) { return m == MODEL_JABRA; }
    static boolean isPixelBuds(int m) { return m == MODEL_PIXEL_BUDS; }
    static boolean isJbl(int m) { return m == MODEL_JBL; }

    static boolean prefersHwPrivateUuid(int m) {
        return m == MODEL_HW_4I || m == MODEL_HW_5I || m == MODEL_HW_SE || m == MODEL_HW_LACE;
    }
    static boolean supportsAnc(int m) {
        if (m == MODEL_UNKNOWN) return false;
        if (isRealme(m)) return true;
        if (isHuawei(m)) return m != MODEL_HW_3 && m != MODEL_HW_SE && m != MODEL_HW_SE2 && m != MODEL_HW_CLIP && m != MODEL_HW_CLIP2;
        if (isHonorChoice(m)) return m != MODEL_HONOR_X2;
        if (isNothing(m)) return m != MODEL_NOTHING_OPEN;
        return isGalaxy(m) || isSony(m) || isBose(m) || isSoundcore(m) || isXiaomi(m)
            || isSennheiser(m) || isJabra(m) || isPixelBuds(m) || isJbl(m);
    }
    static boolean supportsAncLevel(int m) {
        return (isHuawei(m) && supportsAnc(m) && m != MODEL_HW_4I)
            || (isNothing(m) && supportsAnc(m))
            || isXiaomi(m);
    }
    static boolean supportsAncDynamic(int m) {
        if (isNothing(m) || isXiaomi(m)) return supportsAnc(m);
        return m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO || m == MODEL_HW_PRO3
            || m == MODEL_HW_PRO5 || m == MODEL_HW_SE4 || m == MODEL_HW_STUDIO
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsEq(int m) {
        return isRealme(m) || isHonorChoice(m) || isNothing(m) || isGalaxy(m)
            || isSony(m) || isXiaomi(m) || isJbl(m)
            || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO2
            || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2
            || m == MODEL_HW_SE2 || m == MODEL_HW_SE4 || m == MODEL_HW_STUDIO
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean hwEqPro3(int m) { return m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP; }
    static boolean supportsDoubleTap(int m) {
        return isRealme(m) || m == MODEL_HW_3 || m == MODEL_HW_4I || m == MODEL_HW_5I || m == MODEL_HW_6I
            || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2
            || m == MODEL_HW_SE || m == MODEL_HW_SE2 || m == MODEL_HW_SE4 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsTripleTap(int m) {
        return isRealme(m) || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_CLIP2
            || m == MODEL_HW_SE2 || m == MODEL_HW_SE4 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsLongTap(int m) {
        return isRealme(m) || m == MODEL_HW_4I || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO
            || m == MODEL_HW_PRO2 || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP
            || m == MODEL_HW_CLIP2 || m == MODEL_HW_SE4 || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsTouch(int m) { return supportsDoubleTap(m) || supportsTripleTap(m) || supportsLongTap(m); }
    static boolean supportsSpatial(int m) { return isRealme(m) || isSennheiser(m) || isJbl(m); }
    static boolean supportsGameMode(int m) {
        return isRealme(m) || isNothing(m) || isSoundcore(m)
            || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5
            || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2 || m == MODEL_HW_SE2 || m == MODEL_HW_SE4
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsHqAudio(int m) {
        return m == MODEL_T200 || m == MODEL_AIR_7_PRO || m == MODEL_AIR_8_PRO || m == MODEL_REALME_ADV
            || m == MODEL_ONEPLUS_PRO || m == MODEL_OPPO_PRO
            || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO2 || m == MODEL_HW_PRO3
            || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean usesLhdc(int m) { return m == MODEL_AIR_7_PRO || m == MODEL_AIR_8_PRO || m == MODEL_ONEPLUS_PRO || m == MODEL_OPPO_PRO; }
    static boolean supportsDynamic(int m) {
        return m == MODEL_T200 || m == MODEL_AIR_7_PRO || m == MODEL_AIR_8_PRO || m == MODEL_REALME_ADV || isNothing(m);
    }
    static boolean supportsDual(int m) {
        return m == MODEL_T200 || m == MODEL_AIR_7_PRO || m == MODEL_AIR_8_PRO || m == MODEL_REALME_ADV
            || m == MODEL_ONEPLUS_PRO || m == MODEL_OPPO_PRO || m == MODEL_GALAXY_MODERN
            || isBose(m) || isXiaomi(m) || isJabra(m) || isPixelBuds(m)
            || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO || m == MODEL_HW_PRO2
            || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsAutoPlayPause(int m) {
        return m == MODEL_AIR_7_PRO || m == MODEL_AIR_8_PRO || m == MODEL_REALME_ADV || m == MODEL_ONEPLUS_PRO || m == MODEL_OPPO_PRO
            || isNothing(m) || isXiaomi(m) || isPixelBuds(m) || isJbl(m)
            || m == MODEL_HW_4I || m == MODEL_HW_5I || m == MODEL_HW_6I
            || m == MODEL_HW_PRO2 || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP
            || m == MODEL_HW_CLIP2 || m == MODEL_HW_STUDIO || m == MODEL_HW_GENERIC;
    }
    static boolean supportsAutoAnswer(int m) { return m == MODEL_AIR_7_PRO || m == MODEL_AIR_8_PRO || isXiaomi(m); }
    static boolean supportsUseTime(int m) { return isRealme(m); }

    static final class Frame {
        final int cmd;
        final byte[] payload;
        Frame(int cmd, byte[] payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    static final class MultiDevice {
        final String mac;
        final String name;
        final int state;
        MultiDevice(String mac, String name, int state) {
            this.mac = mac;
            this.name = name;
            this.state = state;
        }
    }

    static final class State {
        int leftBat = -1, rightBat = -1, caseBat = -1;
        boolean leftChg, rightChg, caseChg;
        int ancMode = -1; // -1 unknown, 0x08, 0x01, 0x02
        int ancLevel = -1; // Huawei ANC level: 0=General, 1=Comfort, 2=Ultra, 3=Dynamic
        Boolean gameMode;
        Boolean spatialAudio;
        Boolean hqAudio;
        Boolean dynamicAudio;
        Boolean dualConn;
        Boolean autoPlayPause;
        Boolean autoAnswer;
        int eqId = -1; // 0, 1, 2, 3 or -1
        String customEqName;
        int leftUseMin = -1, rightUseMin = -1;
        // touch[side 0=L/1=R][gesture 0=dbl/1=trp/2=hold] = functionId (-1 unknown)
        final int[][] touch = {{-1, -1, -1}, {-1, -1, -1}};
        List<MultiDevice> multiDevices = new ArrayList<>();

        State copy() {
            State s = new State();
            s.leftBat = leftBat; s.rightBat = rightBat; s.caseBat = caseBat;
            s.leftChg = leftChg; s.rightChg = rightChg; s.caseChg = caseChg;
            s.ancMode = ancMode;
            s.ancLevel = ancLevel;
            s.gameMode = gameMode;
            s.spatialAudio = spatialAudio;
            s.hqAudio = hqAudio;
            s.dynamicAudio = dynamicAudio;
            s.dualConn = dualConn;
            s.autoPlayPause = autoPlayPause;
            s.autoAnswer = autoAnswer;
            s.eqId = eqId;
            s.customEqName = customEqName;
            s.leftUseMin = leftUseMin;
            s.rightUseMin = rightUseMin;
            for (int i = 0; i < 2; i++) System.arraycopy(touch[i], 0, s.touch[i], 0, 3);
            s.multiDevices = new ArrayList<>(multiDevices);
            return s;
        }
    }

    static byte[] buildFrame(int cmd, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] out = new byte[5 + len];
        out[0] = (byte) (cmd & 0xFF);
        out[1] = (byte) ((cmd >> 8) & 0xFF);
        out[2] = 0x00;
        out[3] = (byte) (len & 0xFF);
        out[4] = (byte) ((len >> 8) & 0xFF);
        if (len > 0) System.arraycopy(payload, 0, out, 5, len);
        return out;
    }

    static byte[] wrapOppo(byte[] tl) {
        int val = tl.length + 2;
        byte[] var = new byte[4];
        int vc = 0;
        do {
            int b = val & 0x7F;
            val >>>= 7;
            if (val != 0) b |= 0x80;
            var[vc++] = (byte) b;
        } while (val != 0);
        byte[] out = new byte[1 + vc + 2 + tl.length];
        out[0] = OPPO_SOF;
        System.arraycopy(var, 0, out, 1, vc);
        out[1 + vc] = 0;
        out[2 + vc] = 0;
        System.arraycopy(tl, 0, out, 3 + vc, tl.length);
        return out;
    }

    static Frame parseSingleFrame(byte[] data) {
        byte[] raw = unwrapOppo(data);
        if (raw == null) raw = data;
        if (raw == null || raw.length < 5) return null;
        int cmd = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
        int len = (raw[3] & 0xFF) | ((raw[4] & 0xFF) << 8);
        if (raw.length < 5 + len) return null;
        return new Frame(cmd, Arrays.copyOfRange(raw, 5, 5 + len));
    }

    private static byte[] unwrapOppo(byte[] data) {
        if (data == null || data.length < 4 || data[0] != OPPO_SOF) return null;
        int off = 1;
        while (off < data.length) {
            int b = data[off++] & 0xFF;
            if ((b & 0x80) == 0) break;
        }
        if (off + 2 > data.length) return null;
        int flags = data[off] & 0xFF;
        int skip = (flags & 0x03) != 0 ? 3 : 2;
        int start = off + skip;
        if (start > data.length) return null;
        return Arrays.copyOfRange(data, start, data.length);
    }

    static byte[] parseStream(byte[] buf, List<Frame> outFrames) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (buf[off] == OPPO_SOF) {
                int vOff = off + 1, linkLen = 0, shift = 0, vc = 0;
                boolean ended = false;
                while (vOff < total && vc < 4) {
                    int b = buf[vOff++] & 0xFF;
                    linkLen |= (b & 0x7F) << shift;
                    vc++;
                    if ((b & 0x80) == 0) { ended = true; break; }
                    shift += 7;
                }
                if (!ended) break;
                int pktLen = 1 + vc + linkLen;
                if (off + pktLen <= total) {
                    Frame f = parseSingleFrame(Arrays.copyOfRange(buf, off, off + pktLen));
                    if (f != null) outFrames.add(f);
                    off += pktLen;
                } else break;
            } else if (off + 5 <= total) {
                int cmd = (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
                int len = (buf[off + 3] & 0xFF) | ((buf[off + 4] & 0xFF) << 8);
                int fLen = 5 + len;
                if (off + fLen <= total) {
                    outFrames.add(new Frame(cmd, Arrays.copyOfRange(buf, off + 5, off + fLen)));
                    off += fLen;
                } else break;
            } else break;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    static Boolean parseSwitch(byte[] p, byte targetId) {
        if (p.length < 4 || p[0] != 0) return null;
        int count = p[1] & 0xFF;
        int off = 2;
        for (int i = 0; i < count && off + 1 < p.length; i++, off += 2) {
            if (p[off] == targetId) return (p[off + 1] & 0xFF) == 1;
        }
        return null;
    }

    static int parseAncEvent(byte[] p) {
        if (p.length < 3 || p[0] != 0x03) return -1;
        int b = p.length >= 4 ? (p[1] == 1 ? (p[3] & 0xFF) : -1) : (p[2] & 0xFF);
        return (b == ANC_ON || b == ANC_OFF || b == ANC_TRANSPARENCY) ? b : -1;
    }

    static List<MultiDevice> parseMultiConnect(byte[] p, boolean hasStatus) {
        int countOff = hasStatus ? 1 : 0;
        if (hasStatus && (p.length == 0 || p[0] != 0)) return null;
        if (p.length <= countOff) return null;
        int count = p[countOff] & 0xFF;
        List<MultiDevice> list = new ArrayList<>();
        int off = countOff + 1;
        for (int i = 0; i < count; i++) {
            if (off + 10 > p.length) break;
            StringBuilder mac = new StringBuilder();
            for (int m = 5; m >= 0; m--) {
                if (mac.length() > 0) mac.append(':');
                mac.append(String.format("%02X", p[off + m] & 0xFF));
            }
            int entryLen = p[off + 6] & 0xFF;
            int connState = p[off + 7] & 0xFF;
            int nameLen = p[off + 9] & 0xFF;
            int nameStart = off + 10;
            int nameEnd = nameStart + nameLen;
            if (nameEnd > p.length) break;
            String name = new String(p, nameStart, nameLen, StandardCharsets.UTF_8).replace("\0", "");
            list.add(new MultiDevice(mac.toString(), name, connState));
            int extra = entryLen - nameLen - 3;
            off = nameEnd + Math.max(0, extra);
        }
        return list;
    }

    static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    static String ancName(int mode) {
        if (mode == ANC_ON) return "NOISE_REDUCTION";
        if (mode == ANC_OFF) return "OFF";
        if (mode == ANC_TRANSPARENCY) return "TRANSPARENCY";
        return "UNKNOWN";
    }
}
