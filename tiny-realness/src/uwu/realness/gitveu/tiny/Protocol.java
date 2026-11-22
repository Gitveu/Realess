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
    static final int MODEL_T300 = 1;
    static final int MODEL_T200 = 2;
    static final int MODEL_AIR_7_PRO = 3;
    // Huawei / Honor (OpenFreebuds)
    static final int MODEL_HW_3 = 10;
    static final int MODEL_HW_4I = 11;
    static final int MODEL_HW_5I = 12;
    static final int MODEL_HW_6I = 13;
    static final int MODEL_HW_PRO = 14;
    static final int MODEL_HW_PRO2 = 15;
    static final int MODEL_HW_PRO3 = 16;
    static final int MODEL_HW_PRO5 = 17;
    static final int MODEL_HW_CLIP = 18;
    static final int MODEL_HW_CLIP2 = 19;
    static final int MODEL_HW_SE = 20;
    static final int MODEL_HW_SE2 = 21;
    static final int MODEL_HW_SE4 = 22;
    static final int MODEL_HW_STUDIO = 23;
    static final int MODEL_HW_LACE = 24;
    static final int MODEL_HW_LACE2 = 25;
    static final int MODEL_HONOR_S2 = 26;
    static final int MODEL_HW_GENERIC = 27;

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
        if (n.contains("honor") || n.contains("荣耀")) {
            if (n.contains("earbuds s2")) return MODEL_HONOR_S2;
            if (n.contains("earbuds 2")) return MODEL_HW_4I;
            return MODEL_UNKNOWN;
        }
        boolean isRealmeOrBuds = n.contains("realme") || n.contains("buds");
        if (!isRealmeOrBuds) return MODEL_UNKNOWN;
        if (n.contains("air 7 pro") || n.contains("air7 pro")) return MODEL_AIR_7_PRO;
        if (n.contains("t200")) return MODEL_T200;
        if (n.contains("t300")) return MODEL_T300;
        return MODEL_UNKNOWN;
    }

    static boolean isHuawei(int m) { return m >= MODEL_HW_3; }
    static boolean isRealme(int m) { return m >= MODEL_T300 && m <= MODEL_AIR_7_PRO; }
    static boolean prefersHwPrivateUuid(int m) {
        return m == MODEL_HW_4I || m == MODEL_HW_5I || m == MODEL_HW_SE || m == MODEL_HW_LACE;
    }
    static boolean supportsAnc(int m) {
        return isRealme(m) || (isHuawei(m) && m != MODEL_HW_3 && m != MODEL_HW_SE
            && m != MODEL_HW_SE2 && m != MODEL_HW_CLIP && m != MODEL_HW_CLIP2);
    }
    static boolean supportsAncLevel(int m) { return isHuawei(m) && supportsAnc(m) && m != MODEL_HW_4I; }
    static boolean supportsAncDynamic(int m) {
        return m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO || m == MODEL_HW_PRO3
            || m == MODEL_HW_PRO5 || m == MODEL_HW_SE4 || m == MODEL_HW_STUDIO
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsEq(int m) {
        return isRealme(m) || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO2
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
    static boolean supportsSpatial(int m) { return isRealme(m); }
    static boolean supportsGameMode(int m) {
        return isRealme(m) || m == MODEL_HW_5I || m == MODEL_HW_6I || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5
            || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2 || m == MODEL_HW_SE2 || m == MODEL_HW_SE4
            || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsHqAudio(int m) {
        return m == MODEL_T200 || m == MODEL_AIR_7_PRO || m == MODEL_HW_5I || m == MODEL_HW_6I
            || m == MODEL_HW_PRO2 || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP
            || m == MODEL_HW_CLIP2 || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean usesLhdc(int m) { return m == MODEL_AIR_7_PRO; }
    static boolean supportsDynamic(int m) { return m == MODEL_T200 || m == MODEL_AIR_7_PRO; }
    static boolean supportsDual(int m) {
        return m == MODEL_T200 || m == MODEL_AIR_7_PRO || m == MODEL_HW_5I || m == MODEL_HW_6I
            || m == MODEL_HW_PRO || m == MODEL_HW_PRO2 || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5
            || m == MODEL_HW_CLIP || m == MODEL_HW_CLIP2 || m == MODEL_HW_LACE2 || m == MODEL_HW_GENERIC;
    }
    static boolean supportsAutoPlayPause(int m) {
        return m == MODEL_AIR_7_PRO || m == MODEL_HW_4I || m == MODEL_HW_5I || m == MODEL_HW_6I
            || m == MODEL_HW_PRO2 || m == MODEL_HW_PRO3 || m == MODEL_HW_PRO5 || m == MODEL_HW_CLIP
            || m == MODEL_HW_CLIP2 || m == MODEL_HW_STUDIO || m == MODEL_HW_GENERIC;
    }
    static boolean supportsAutoAnswer(int m) { return m == MODEL_AIR_7_PRO; }
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
