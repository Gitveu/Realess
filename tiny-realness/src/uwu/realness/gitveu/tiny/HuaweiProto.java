package uwu.realness.gitveu.tiny;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class HuaweiProto {
    static final byte SOF = 0x5A;
    static final int CMD_BAT_READ = 0x0108, CMD_BAT_NOTIFY = 0x0127;
    static final int CMD_DTAP_W = 0x011F, CMD_DTAP_R = 0x0120;
    static final int CMD_TTAP_W = 0x0125, CMD_TTAP_R = 0x0126;
    static final int CMD_ANC_EV = 0x2B03, CMD_ANC_W = 0x2B04, CMD_ANC_R = 0x2B2A;
    static final int CMD_AUTOPAUSE_W = 0x2B10, CMD_AUTOPAUSE_R = 0x2B11;
    static final int CMD_LTAP_W = 0x2B16, CMD_LTAP_R = 0x2B17;
    static final int CMD_DC_W = 0x2B2E, CMD_DC_R = 0x2B2F, CMD_DC_ENUM = 0x2B31, CMD_DC_EV = 0x2B36;
    static final int CMD_EQ_W = 0x2B49, CMD_EQ_R = 0x2B4A;
    static final int CMD_LOW_LAT = 0x2B6C;
    static final int CMD_SQP_W = 0x2BA2, CMD_SQP_R = 0x2BA3;

    static final class Pkt {
        final int cmd;
        final Map<Integer, byte[]> params;
        Pkt(int cmd, Map<Integer, byte[]> params) { this.cmd = cmd; this.params = params; }
        byte[] get(int k) { return params.get(k); }
    }

    static int crc16(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    static byte[] build(int cmd, int[] types, byte[][] vals) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((cmd >>> 8) & 0xFF);
        body.write(cmd & 0xFF);
        for (int i = 0; i < types.length; i++) {
            byte[] v = vals[i];
            body.write(types[i] & 0xFF);
            body.write(v == null ? 0 : v.length);
            if (v != null && v.length > 0) body.write(v, 0, v.length);
        }
        byte[] b = body.toByteArray();
        int len = b.length + 1;
        byte[] out = new byte[4 + b.length + 2];
        out[0] = SOF;
        out[1] = (byte) ((len >>> 8) & 0xFF);
        out[2] = (byte) (len & 0xFF);
        out[3] = 0;
        System.arraycopy(b, 0, out, 4, b.length);
        int crc = crc16(out, 4 + b.length);
        out[4 + b.length] = (byte) ((crc >>> 8) & 0xFF);
        out[5 + b.length] = (byte) (crc & 0xFF);
        return out;
    }

    static byte[] readReq(int cmd, int... types) {
        return build(cmd, types, new byte[types.length][]);
    }

    static byte[] writeByte(int cmd, int type, byte val) {
        return build(cmd, new int[]{type}, new byte[][]{new byte[]{val}});
    }

    static byte[] writeAncMode(byte realmeMode) {
        byte m = realmeMode == Protocol.ANC_ON ? (byte) 1 : (realmeMode == Protocol.ANC_TRANSPARENCY ? (byte) 2 : (byte) 0);
        byte s = m == 0 ? (byte) 0 : (byte) 0xFF;
        return build(CMD_ANC_W, new int[]{1}, new byte[][]{new byte[]{m, s}});
    }

    static byte[] writeAncLevel(byte lvl) {
        return build(CMD_ANC_W, new int[]{1}, new byte[][]{new byte[]{1, lvl}});
    }

    static byte[] parseStream(byte[] buf, List<Pkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (buf[off] != SOF) { off++; continue; }
            if (off + 4 > total) break;
            if (buf[off + 3] != 0) { off++; continue; }
            int len = ((buf[off + 1] & 0xFF) << 8) | (buf[off + 2] & 0xFF);
            if (len < 3 || len > 2048) { off++; continue; }
            int full = len + 5;
            if (off + full > total) break;
            int cmd = ((buf[off + 4] & 0xFF) << 8) | (buf[off + 5] & 0xFF);
            Map<Integer, byte[]> map = new LinkedHashMap<>();
            int pos = off + 6, end = off + 3 + len;
            while (pos + 1 < end) {
                int pt = buf[pos] & 0xFF;
                int pl = buf[pos + 1] & 0xFF;
                int vs = pos + 2, ve = vs + pl;
                if (ve > end) break;
                map.put(pt, Arrays.copyOfRange(buf, vs, ve));
                pos = ve;
            }
            out.add(new Pkt(cmd, map));
            off += full;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    static byte eqToHw(byte realmeEqId, boolean pro3) {
        if (pro3) {
            if (realmeEqId == 0) return 5;
            if (realmeEqId == 1) return 1;
            if (realmeEqId == 3) return 2;
            return 9;
        }
        if (realmeEqId == 0) return 1;
        if (realmeEqId == 1) return 2;
        if (realmeEqId == 3) return 3;
        return 9;
    }

    static int hwToEq(int id, boolean pro3) {
        if (pro3) {
            if (id == 5) return 0;
            if (id == 1) return 1;
            if (id == 2) return 3;
            if (id == 9) return 2;
            return -1;
        }
        if (id == 1) return 0;
        if (id == 2) return 1;
        if (id == 3) return 3;
        if (id == 9) return 2;
        return -1;
    }

    static String findCustomEqName(byte[] p8, int targetId) {
        if (p8 == null) return null;
        for (int off = 0; off + 36 <= p8.length; off += 36) {
            int id = p8[off];
            if (id == targetId) {
                int cnt = p8[off + 1] & 0xFF;
                int s = off + 2 + cnt, e = off + 36;
                if (s < e) {
                    int z = s;
                    while (z < e && p8[z] != 0) z++;
                    String n = new String(p8, s, z - s, StandardCharsets.UTF_8).trim();
                    if (!n.isEmpty()) return n;
                }
            }
        }
        return null;
    }

    static int hwTapToFn(int code, int gestIdx, boolean fb3) {
        if (gestIdx == 2) {
            if (code == 10) return 0x08;
            if (code == 0) return 0x03;
            if (code == 18) return 0x0B;
            if (code == 19) return 0x0C;
            return 0x00;
        }
        if (fb3) {
            if (code == 0) return 0x03;
            if (code == 1) return 0x01;
            if (code == 3) return 0x08;
            if (code == 4) return 0x06;
            return 0x00;
        }
        if (code == 1) return 0x01;
        if (code == 2) return 0x06;
        if (code == 7) return 0x05;
        if (code == 0) return 0x03;
        return 0x00;
    }

    static byte fnToHwTap(byte fn, int gestIdx, boolean fb3) {
        if (gestIdx == 2) {
            if (fn == 0x08) return 10;
            if (fn == 0x03) return 0;
            if (fn == 0x0B) return 18;
            if (fn == 0x0C) return 19;
            return -1;
        }
        if (fb3) {
            if (fn == 0x03) return 0;
            if (fn == 0x01) return 1;
            if (fn == 0x08) return 3;
            if (fn == 0x06) return 4;
            return -1;
        }
        if (fn == 0x01) return 1;
        if (fn == 0x06) return 2;
        if (fn == 0x05) return 7;
        if (fn == 0x03) return 0;
        return -1;
    }
}
