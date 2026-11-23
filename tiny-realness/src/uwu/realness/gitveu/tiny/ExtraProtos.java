package uwu.realness.gitveu.tiny;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Compact pure Java 8 implementations of the 8 extra protocol families from kde-headphones:
 * Sony MDR, Bose BMAP, Anker Soundcore, Xiaomi/Redmi (+ SAFER+ auth),
 * Sennheiser GAIA, Jabra, Google Pixel Buds (HDLC + Pigweed RPC), and JBL (BLE GATT).
 */
final class ExtraProtos {

    // ================= 1. Sony MDR (V1/V2) =================
    static final class SonyPkt {
        final int type, seq;
        final byte[] payload;
        SonyPkt(int type, int seq, byte[] payload) { this.type = type; this.seq = seq; this.payload = payload; }
    }

    static byte[] sonyPack(byte type, int seq, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] raw = new byte[6 + len + 1];
        raw[0] = type;
        raw[1] = (byte) (seq & 0xFF);
        raw[2] = (byte) ((len >>> 24) & 0xFF);
        raw[3] = (byte) ((len >>> 16) & 0xFF);
        raw[4] = (byte) ((len >>> 8) & 0xFF);
        raw[5] = (byte) (len & 0xFF);
        if (len > 0) System.arraycopy(payload, 0, raw, 6, len);
        int sum = 0;
        for (int i = 0; i < 6 + len; i++) sum = (sum + (raw[i] & 0xFF)) & 0xFF;
        raw[6 + len] = (byte) sum;
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 8);
        out.write(0x3E);
        for (byte b : raw) {
            int u = b & 0xFF;
            if (u == 0x3C || u == 0x3D || u == 0x3E) {
                out.write(0x3D);
                out.write(u & 0xEF);
            } else out.write(u);
        }
        out.write(0x3C);
        return out.toByteArray();
    }

    static byte[] sonyAck(int rxSeq) { return sonyPack((byte) 0x01, 1 - (rxSeq & 1), null); }
    static byte[] sonyBatReq(int seq, byte inq) { return sonyPack((byte) 0x0C, seq, new byte[]{0x22, inq}); }
    static byte[] sonyAncReq(int seq) { return sonyPack((byte) 0x0C, seq, new byte[]{0x66, 0x17}); }
    static byte[] sonyEqReq(int seq) { return sonyPack((byte) 0x0C, seq, new byte[]{0x56, 0x01}); }

    static byte[] sonyAncSet(int seq, byte mode) {
        byte[] p = mode == Protocol.ANC_OFF
            ? new byte[]{0x68, 0x17, 0x01, 0x00, 0x00, 0x00, 0x00}
            : (mode == Protocol.ANC_ON
                ? new byte[]{0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x00}
                : new byte[]{0x68, 0x17, 0x01, 0x01, 0x01, 0x00, 0x10});
        return sonyPack((byte) 0x0C, seq, p);
    }

    static byte[] sonyEqSet(int seq, byte eqId) {
        byte c = eqId == 0 ? 0x00 : (eqId == 2 ? 0x04 : (eqId == 3 ? 0x01 : (byte) 0x05));
        return sonyPack((byte) 0x0C, seq, new byte[]{0x58, 0x01, c, 0x00});
    }

    static byte[] sonyParseStream(byte[] buf, List<SonyPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            int start = -1;
            for (int i = off; i < total; i++) if (buf[i] == 0x3E) { start = i; break; }
            if (start < 0) return new byte[0];
            int end = -1;
            for (int i = start + 1; i < total; i++) if (buf[i] == 0x3C) { end = i; break; }
            if (end < 0) return Arrays.copyOfRange(buf, start, total);
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
            boolean bad = false;
            for (int i = start + 1; i < end; i++) {
                if (buf[i] == 0x3D) {
                    if (++i >= end) { bad = true; break; }
                    rawOut.write((buf[i] & 0xFF) | 0x10);
                } else rawOut.write(buf[i] & 0xFF);
            }
            if (!bad) {
                byte[] raw = rawOut.toByteArray();
                if (raw.length >= 7) {
                    int len = ((raw[2] & 0xFF) << 24) | ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
                    if (len >= 0 && 6 + len + 1 <= raw.length) {
                        int sum = 0;
                        for (int i = 0; i < 6 + len; i++) sum = (sum + (raw[i] & 0xFF)) & 0xFF;
                        if (sum == (raw[6 + len] & 0xFF)) {
                            byte[] p = len > 0 ? Arrays.copyOfRange(raw, 6, 6 + len) : new byte[0];
                            out.add(new SonyPkt(raw[0] & 0xFF, raw[1] & 0xFF, p));
                        }
                    }
                }
            }
            off = end + 1;
        }
        return new byte[0];
    }

    // ================= 2. Bose BMAP =================
    static final class BosePkt {
        final int block, func, op;
        final byte[] payload;
        BosePkt(int block, int func, int op, byte[] payload) { this.block = block; this.func = func; this.op = op; this.payload = payload; }
    }

    static byte[] boseBuild(int block, int func, int op, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] out = new byte[4 + len];
        out[0] = (byte) block; out[1] = (byte) func; out[2] = (byte) op; out[3] = (byte) len;
        if (len > 0) System.arraycopy(payload, 0, out, 4, len);
        return out;
    }

    static byte[] boseAncSet(byte mode) {
        byte lvl = mode == Protocol.ANC_ON ? (byte) 0x01 : (mode == Protocol.ANC_TRANSPARENCY ? (byte) 0x03 : (byte) 0x00);
        return boseBuild(0x01, 0x06, 0x02, new byte[]{lvl});
    }

    static byte[] boseRename(String name) {
        byte[] raw = name.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (raw.length > 31) raw = Arrays.copyOf(raw, 31);
        return boseBuild(0x01, 0x02, 0x02, raw);
    }

    static byte[] boseEqBandSet(int idx, int db) {
        int g = Math.max(-10, Math.min(10, db));
        return boseBuild(0x01, 0x07, 0x02, new byte[]{(byte) g, (byte) idx});
    }

    static byte[] boseParseStream(byte[] buf, List<BosePkt> out) {
        int off = 0, total = buf.length;
        while (off + 4 <= total) {
            int len = buf[off + 3] & 0xFF;
            if (off + 4 + len > total) break;
            byte[] p = len > 0 ? Arrays.copyOfRange(buf, off + 4, off + 4 + len) : new byte[0];
            out.add(new BosePkt(buf[off] & 0xFF, buf[off + 1] & 0xFF, buf[off + 2] & 0xFF, p));
            off += 4 + len;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    // ================= 3. Anker Soundcore =================
    static final class ScPkt {
        final int group, cmd;
        final byte[] payload;
        ScPkt(int group, int cmd, byte[] payload) { this.group = group; this.cmd = cmd; this.payload = payload; }
    }

    static byte[] scBuild(int group, int cmd, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        int total = 10 + plen;
        byte[] out = new byte[total];
        out[0] = 0x08; out[1] = (byte) 0xEE; out[2] = 0x00; out[3] = 0x00; out[4] = 0x00;
        out[5] = (byte) group; out[6] = (byte) cmd;
        out[7] = (byte) (total & 0xFF); out[8] = (byte) ((total >>> 8) & 0xFF);
        if (plen > 0) System.arraycopy(payload, 0, out, 9, plen);
        int sum = 0;
        for (int i = 0; i < total - 1; i++) sum = (sum + (out[i] & 0xFF)) & 0xFF;
        out[total - 1] = (byte) sum;
        return out;
    }

    static byte[] scAncSet(byte mode) {
        byte m = mode == Protocol.ANC_ON ? 0x00 : (mode == Protocol.ANC_TRANSPARENCY ? (byte) 0x01 : (byte) 0x02);
        return scBuild(0x06, 0x81, new byte[]{m, 0x00, 0x01, 0x00});
    }

    static byte[] scParseStream(byte[] buf, List<ScPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (off + 5 > total) break;
            if ((buf[off] & 0xFF) != 0x09 || (buf[off + 1] & 0xFF) != 0xFF || buf[off + 2] != 0 || buf[off + 3] != 0 || buf[off + 4] != 1) {
                off++; continue;
            }
            if (off + 10 > total) break;
            int len = (buf[off + 7] & 0xFF) | ((buf[off + 8] & 0xFF) << 8);
            if (len < 10 || len > 512) { off++; continue; }
            if (off + len > total) break;
            int sum = 0;
            for (int i = 0; i < len - 1; i++) sum = (sum + (buf[off + i] & 0xFF)) & 0xFF;
            if (sum == (buf[off + len - 1] & 0xFF)) {
                int plen = len - 10;
                byte[] p = plen > 0 ? Arrays.copyOfRange(buf, off + 9, off + 9 + plen) : new byte[0];
                out.add(new ScPkt(buf[off + 5] & 0xFF, buf[off + 6] & 0xFF, p));
                off += len;
            } else off++;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    // ================= 4. Xiaomi / Redmi (+ SAFER+ auth) =================
    static final class XiaomiPkt {
        final int type, opcode, seq;
        final byte[] payload;
        XiaomiPkt(int type, int opcode, int seq, byte[] payload) {
            this.type = type; this.opcode = opcode; this.seq = seq; this.payload = payload;
        }
        boolean isResp() { return (type & 0x40) == 0; }
    }

    private static final AtomicInteger xSeq = new AtomicInteger(1);
    private static final int[] EXP_TAB = new int[256];
    private static final int[] LOG_TAB = new int[256];
    private static final int[][] BIAS_TAB = new int[16][16];
    static {
        for (int i = 0; i < 256; i++) {
            int v = powMod(45, i, 257);
            EXP_TAB[i] = v == 256 ? 0 : v;
            LOG_TAB[EXP_TAB[i]] = i;
        }
        for (int k = 0; k < 16; k++) {
            for (int j = 0; j < 16; j++) {
                int v = powMod(45, powMod(45, 17 * (k + 2) + j + 1, 257), 257);
                BIAS_TAB[k][j] = v == 256 ? 0 : v;
            }
        }
    }

    private static int powMod(int b, int e, int m) {
        int res = 1, base = b % m, exp = e;
        while (exp > 0) {
            if ((exp & 1) != 0) res = (res * base) % m;
            base = (base * base) % m;
            exp >>>= 1;
        }
        return res;
    }

    private static boolean xorLane(int i) { return ((0x9999 >>> i) & 1) != 0; }

    private static void linearLayer(int[] v) {
        int[] sh = {8, 11, 12, 15, 2, 1, 6, 5, 10, 9, 14, 13, 0, 7, 4, 3};
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 0; i < 16; i += 2) {
                int a = v[i], b = v[i + 1];
                v[i] = (2 * a + b) & 0xFF;
                v[i + 1] = (a + b) & 0xFF;
            }
            if (pass == 3) break;
            int[] cp = Arrays.copyOf(v, 16);
            for (int j = 0; j < 16; j++) v[j] = cp[sh[j]];
        }
    }

    static byte[] xiaomiChallengeResponse(byte[] ch) {
        int[] in = {0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33};
        int[][] keys = new int[17][16];
        for (int i = 0; i < 16; i++) keys[0][i] = ch[i] & 0xFF;
        keys[0][15] ^= 6;
        int[] reg = new int[17];
        for (int i = 0; i < 16; i++) { reg[i] = keys[0][i]; reg[16] ^= keys[0][i]; }
        for (int k = 1; k <= 16; k++) {
            for (int i = 0; i < 17; i++) reg[i] = ((reg[i] << 3) | (reg[i] >>> 5)) & 0xFF;
            for (int i = 0; i < 16; i++) keys[k][i] = (reg[(k + i) % 17] + BIAS_TAB[k - 1][i]) & 0xFF;
        }
        int[] v = Arrays.copyOf(in, 16);
        for (int r = 0; r < 8; r++) {
            if (r == 2) {
                for (int i = 0; i < 16; i++) v[i] = xorLane(i) ? (v[i] ^ in[i]) : ((v[i] + in[i]) & 0xFF);
            }
            int[] k1 = keys[2 * r], k2 = keys[2 * r + 1];
            for (int i = 0; i < 16; i++) {
                v[i] = xorLane(i) ? ((EXP_TAB[v[i] ^ k1[i]] + k2[i]) & 0xFF) : (LOG_TAB[(v[i] + k1[i]) & 0xFF] ^ k2[i]);
            }
            linearLayer(v);
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (xorLane(i) ? (v[i] ^ keys[16][i]) : ((v[i] + keys[16][i]) & 0xFF));
        }
        return out;
    }

    static byte[] xiaomiBuild(int type, int opcode, int seq, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        boolean resp = (type & 0x40) == 0;
        int blen = (resp ? 2 : 1) + plen;
        byte[] out = new byte[7 + blen + 1];
        out[0] = (byte) 0xFE; out[1] = (byte) 0xDC; out[2] = (byte) 0xBA;
        out[3] = (byte) type; out[4] = (byte) opcode;
        out[5] = (byte) ((blen >>> 8) & 0xFF); out[6] = (byte) (blen & 0xFF);
        int idx = 7;
        if (resp) out[idx++] = 0x00;
        out[idx++] = (byte) seq;
        if (plen > 0) System.arraycopy(payload, 0, out, idx, plen);
        out[out.length - 1] = (byte) 0xEF;
        return out;
    }

    static byte[] xiaomiReq(int opcode, byte[] payload) {
        return xiaomiBuild(0xC4, opcode, xSeq.getAndIncrement() & 0xFF, payload);
    }

    static byte[] xiaomiAuthChallenge() {
        byte[] rand = new byte[17];
        rand[0] = 0x00;
        for (int i = 1; i <= 16; i++) rand[i] = (byte) (i * 17);
        return xiaomiReq(0x50, rand);
    }

    static byte[] xiaomiGetCfg(int key) {
        return xiaomiReq(0xF3, new byte[]{(byte) ((key >>> 8) & 0xFF), (byte) (key & 0xFF)});
    }

    static byte[] xiaomiSetCfg(int key, byte[] val) {
        byte[] p = new byte[3 + val.length];
        p[0] = (byte) (2 + val.length);
        p[1] = (byte) ((key >>> 8) & 0xFF);
        p[2] = (byte) (key & 0xFF);
        System.arraycopy(val, 0, p, 3, val.length);
        return xiaomiReq(0xF2, p);
    }

    static byte[] xiaomiAncSet(byte mode) {
        byte m = mode == Protocol.ANC_ON ? 0x01 : (mode == Protocol.ANC_TRANSPARENCY ? (byte) 0x02 : (byte) 0x00);
        return xiaomiReq(0x08, new byte[]{0x02, 0x04, m});
    }

    static byte[] xiaomiAncLevelSet(byte lvl) {
        byte v = lvl == 1 ? 0x01 : (lvl == 0 ? 0x00 : (lvl == 2 ? (byte) 0x02 : (byte) 0x03));
        return xiaomiSetCfg(0x0B, new byte[]{0x01, v});
    }

    static byte[] xiaomiEqSet(byte eqId) {
        byte v = eqId == 0 ? 0x00 : (eqId == 2 ? 0x01 : (eqId == 3 ? 0x06 : (byte) 0x05));
        return xiaomiSetCfg(0x07, new byte[]{v});
    }

    static byte[] xiaomiCustomEqSave(int[] freqs, int[] gains) {
        int n = Math.min(freqs.length, gains.length);
        byte[] out = new byte[7 + n * 3];
        out[0] = 1; out[1] = 10; out[2] = 1; out[3] = 1; out[4] = 1; out[5] = 0; out[6] = (byte) n;
        int off = 7;
        for (int i = 0; i < n; i++) {
            int f = freqs[i];
            int g = Math.max(-10, Math.min(10, gains[i]));
            out[off++] = (byte) ((f >>> 8) & 0xFF);
            out[off++] = (byte) (f & 0xFF);
            out[off++] = (byte) (g >= 0 ? g : (128 - g));
        }
        return xiaomiSetCfg(0x37, out);
    }

    static byte[] scCustomEqSet(int[] gains) {
        byte[] b = new byte[10];
        b[0] = (byte) 0xFE; b[1] = (byte) 0xFE;
        for (int i = 0; i < 8; i++) {
            int g = Math.max(-6, Math.min(6, i < gains.length ? gains[i] : 0));
            b[2 + i] = (byte) (g * 10 + 120);
        }
        return scBuild(0x02, 0x81, b);
    }

    static byte[] sonyCustomEqSet(int seq, int[] gains) {
        byte[] p = new byte[10];
        p[0] = 0x58; p[1] = 0x01; p[2] = (byte) 0xA0; p[3] = 6;
        for (int i = 0; i < 6; i++) {
            int g = Math.max(-10, Math.min(10, i < gains.length ? gains[i] : 0));
            p[4 + i] = (byte) (g + 10);
        }
        return sonyPack((byte) 0x0C, seq, p);
    }

    static byte[] xiaomiParseStream(byte[] buf, List<XiaomiPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (off + 3 > total) break;
            if ((buf[off] & 0xFF) != 0xFE || (buf[off + 1] & 0xFF) != 0xDC || (buf[off + 2] & 0xFF) != 0xBA) {
                off++; continue;
            }
            if (off + 8 > total) break;
            int type = buf[off + 3] & 0xFF, op = buf[off + 4] & 0xFF;
            int blen = ((buf[off + 5] & 0xFF) << 8) | (buf[off + 6] & 0xFF);
            int fLen = 7 + blen + 1;
            if (blen < 1 || fLen > 1024) { off++; continue; }
            if (off + fLen > total) break;
            if ((buf[off + fLen - 1] & 0xFF) != 0xEF) { off++; continue; }
            boolean resp = (type & 0x40) == 0;
            int seqOff = off + 7 + (resp ? 1 : 0);
            int pStart = seqOff + 1, pEnd = off + 7 + blen;
            if (pStart <= pEnd) {
                byte[] p = pEnd > pStart ? Arrays.copyOfRange(buf, pStart, pEnd) : new byte[0];
                out.add(new XiaomiPkt(type, op, buf[seqOff] & 0xFF, p));
            }
            off += fLen;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    // ================= 5. Sennheiser GAIA v3 =================
    static final class GaiaPkt {
        final int vendor, feature, pduType, command;
        final byte[] payload;
        GaiaPkt(int v, int f, int pt, int c, byte[] p) { vendor = v; feature = f; pduType = pt; command = c; payload = p; }
    }

    static byte[] gaiaBuild(int vendor, int feat, int pduType, int cmd, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        byte[] out = new byte[8 + plen];
        out[0] = (byte) 0xFF; out[1] = 0x03; out[2] = 0x00; out[3] = (byte) plen;
        out[4] = (byte) ((vendor >>> 8) & 0xFF); out[5] = (byte) (vendor & 0xFF);
        int desc = ((feat & 0x7F) << 9) | ((pduType & 0x03) << 7) | (cmd & 0x7F);
        out[6] = (byte) ((desc >>> 8) & 0xFF); out[7] = (byte) (desc & 0xFF);
        if (plen > 0) System.arraycopy(payload, 0, out, 8, plen);
        return out;
    }

    static byte[] gaiaParseStream(byte[] buf, List<GaiaPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (off + 2 > total) break;
            if ((buf[off] & 0xFF) != 0xFF || (buf[off + 1] & 0xFF) != 0x03) { off++; continue; }
            if (off + 8 > total) break;
            int flags = buf[off + 2] & 0xFF;
            boolean ext = (flags & 0x02) != 0, chk = (flags & 0x01) != 0;
            int hLen = ext ? 9 : 8;
            if (off + hLen > total) break;
            int plen = ext ? (((buf[off + 3] & 0xFF) << 8) | (buf[off + 4] & 0xFF)) : (buf[off + 3] & 0xFF);
            int fLen = hLen + plen + (chk ? 1 : 0);
            if (off + fLen > total) break;
            int vOff = ext ? off + 5 : off + 4;
            int vendor = ((buf[vOff] & 0xFF) << 8) | (buf[vOff + 1] & 0xFF);
            int desc = ((buf[vOff + 2] & 0xFF) << 8) | (buf[vOff + 3] & 0xFF);
            byte[] p = plen > 0 ? Arrays.copyOfRange(buf, off + hLen, off + hLen + plen) : new byte[0];
            out.add(new GaiaPkt(vendor, (desc >>> 9) & 0x7F, (desc >>> 7) & 0x03, desc & 0x7F, p));
            off += fLen;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    // ================= 6. Jabra SPP =================
    static final class JabraPkt {
        final int type, feat;
        final byte[] payload;
        JabraPkt(int t, int f, byte[] p) { type = t; feat = f; payload = p; }
    }

    private static final AtomicInteger jabraSeq = new AtomicInteger(1);

    static byte[] jabraBuild(int type, int feat, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        byte[] out = new byte[6 + plen];
        out[0] = 0x04; out[1] = 0x09; out[2] = (byte) (jabraSeq.getAndIncrement() & 0xFF);
        out[3] = (byte) ((type & 0xF0) | ((plen + 2) & 0x0F));
        out[4] = (byte) ((feat >>> 8) & 0xFF); out[5] = (byte) (feat & 0xFF);
        if (plen > 0) System.arraycopy(payload, 0, out, 6, plen);
        return out;
    }

    static byte[] jabraAncSet(byte mode) {
        byte v = mode == Protocol.ANC_ON ? 0x02 : (mode == Protocol.ANC_TRANSPARENCY ? (byte) 0x01 : (byte) 0x00);
        return jabraBuild(0x20, 0x0104, new byte[]{v});
    }

    static byte[] jabraParseStream(byte[] buf, List<JabraPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (off + 2 > total) break;
            if (buf[off] != 0x04 || buf[off + 1] != 0x09) { off++; continue; }
            if (off + 6 > total) break;
            int tl = buf[off + 3] & 0xFF;
            int len = tl & 0x0F;
            if (len < 2) { off++; continue; }
            int fLen = 4 + len;
            if (off + fLen > total) break;
            int feat = ((buf[off + 4] & 0xFF) << 8) | (buf[off + 5] & 0xFF);
            int plen = len - 2;
            byte[] p = plen > 0 ? Arrays.copyOfRange(buf, off + 6, off + 6 + plen) : new byte[0];
            out.add(new JabraPkt(tl & 0xF0, feat, p));
            off += fLen;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }

    // ================= 7. Google Pixel Buds (HDLC + Pigweed RPC) =================
    static final long PB_SVC_MAESTRO = 0x5639B38EL;
    static final long PB_M_READ = 0x7056A156L;
    static final long PB_M_WRITE = 0x16AD1319L;
    static final long PB_M_SUB_BAT = 0x1E0D2847L;
    static final long PB_M_SUB_SET = 0xB309B9AFL;

    static final class PbPkt {
        final int ch; final long method; final byte[] payload;
        PbPkt(int ch, long m, byte[] p) { this.ch = ch; this.method = m; this.payload = p; }
    }

    static final class PbField {
        final int num, wire; final long v; final byte[] b;
        PbField(int n, int w, long v, byte[] b) { num = n; wire = w; this.v = v; this.b = b; }
    }

    private static void pbVarint(ByteArrayOutputStream o, long v) {
        long x = v;
        while ((x & ~0x7FL) != 0L) { o.write((int) ((x & 0x7FL) | 0x80L)); x >>>= 7; }
        o.write((int) (x & 0x7FL));
    }

    private static void pbVarField(ByteArrayOutputStream o, int f, long v) { pbVarint(o, ((long) f << 3)); pbVarint(o, v); }
    private static void pbFix32(ByteArrayOutputStream o, int f, long v) {
        pbVarint(o, ((long) f << 3) | 5L);
        for (int i = 0; i < 4; i++) o.write((int) ((v >>> (8 * i)) & 0xFFL));
    }
    private static void pbBytes(ByteArrayOutputStream o, int f, byte[] b) {
        pbVarint(o, ((long) f << 3) | 2L); pbVarint(o, b.length); o.write(b, 0, b.length);
    }

    static byte[] pbRpc(int ch, long method, byte[] payload) {
        ByteArrayOutputStream rpc = new ByteArrayOutputStream();
        pbVarField(rpc, 2, ch);
        pbFix32(rpc, 3, PB_SVC_MAESTRO);
        pbFix32(rpc, 4, method);
        if (payload != null && payload.length > 0) pbBytes(rpc, 5, payload);
        byte[] rb = rpc.toByteArray();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int addr = ch == 1 ? 82 : (ch == 2 ? 83 : ch);
        while ((addr >>> 7) != 0) { body.write((addr & 0x7F) << 1); addr >>>= 7; }
        body.write(((addr & 0x7F) << 1) | 1);
        body.write(0x03);
        body.write(rb, 0, rb.length);
        byte[] bb = body.toByteArray();
        CRC32 crc32 = new CRC32();
        crc32.update(bb);
        long crc = crc32.getValue();
        for (int i = 0; i < 4; i++) body.write((int) ((crc >>> (8 * i)) & 0xFFL));
        byte[] raw = body.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 8);
        out.write(0x7E);
        for (byte b : raw) {
            if (b == 0x7E || b == 0x7D) { out.write(0x7D); out.write((b & 0xFF) ^ 0x20); }
            else out.write(b & 0xFF);
        }
        out.write(0x7E);
        return out.toByteArray();
    }

    static byte[] pbReadSetting(int ch, int id) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        pbVarField(m, 4, id);
        return pbRpc(ch, PB_M_READ, m.toByteArray());
    }

    static byte[] pbWriteSetting(int ch, int id, long val) {
        ByteArrayOutputStream sv = new ByteArrayOutputStream();
        pbVarField(sv, id, val);
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        pbBytes(m, 4, sv.toByteArray());
        return pbRpc(ch, PB_M_WRITE, m.toByteArray());
    }

    static List<PbField> pbReadFields(byte[] d) {
        List<PbField> list = new ArrayList<>();
        int pos = 0;
        while (pos < d.length) {
            long tag = 0; int sh = 0; boolean ok = false;
            while (pos < d.length && sh < 64) {
                int b = d[pos++] & 0xFF;
                tag |= (long) (b & 0x7F) << sh;
                if ((b & 0x80) == 0) { ok = true; break; }
                sh += 7;
            }
            if (!ok) break;
            int num = (int) (tag >>> 3), wire = (int) (tag & 7L);
            if (wire == 0) {
                long v = 0; sh = 0; ok = false;
                while (pos < d.length && sh < 64) {
                    int b = d[pos++] & 0xFF;
                    v |= (long) (b & 0x7F) << sh;
                    if ((b & 0x80) == 0) { ok = true; break; }
                    sh += 7;
                }
                if (!ok) break;
                list.add(new PbField(num, 0, v, null));
            } else if (wire == 1) {
                if (pos + 8 > d.length) break;
                pos += 8;
            } else if (wire == 2) {
                long lv = 0; sh = 0; ok = false;
                while (pos < d.length && sh < 64) {
                    int b = d[pos++] & 0xFF;
                    lv |= (long) (b & 0x7F) << sh;
                    if ((b & 0x80) == 0) { ok = true; break; }
                    sh += 7;
                }
                int l = (int) lv;
                if (!ok || l < 0 || pos + l > d.length) break;
                list.add(new PbField(num, 2, 0, Arrays.copyOfRange(d, pos, pos + l)));
                pos += l;
            } else if (wire == 5) {
                if (pos + 4 > d.length) break;
                long v = 0;
                for (int i = 0; i < 4; i++) v |= ((long) (d[pos + i] & 0xFF)) << (8 * i);
                pos += 4;
                list.add(new PbField(num, 5, v, null));
            } else break;
        }
        return list;
    }

    static byte[] pbParseStream(byte[] buf, List<PbPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            int start = -1;
            for (int i = off; i < total; i++) if (buf[i] == 0x7E) { start = i; break; }
            if (start < 0) return new byte[0];
            int end = -1;
            for (int i = start + 1; i < total; i++) if (buf[i] == 0x7E) { end = i; break; }
            if (end < 0) return Arrays.copyOfRange(buf, start, total);
            if (end > start + 1) {
                ByteArrayOutputStream unesc = new ByteArrayOutputStream();
                boolean ok = true;
                for (int i = start + 1; i < end; i++) {
                    if (buf[i] == 0x7D) {
                        if (++i >= end) { ok = false; break; }
                        unesc.write((buf[i] & 0xFF) ^ 0x20);
                    } else unesc.write(buf[i] & 0xFF);
                }
                byte[] raw = unesc.toByteArray();
                if (ok && raw.length >= 6) {
                    int dataEnd = raw.length - 4, n = 0;
                    while (n < dataEnd && n < 5 && (raw[n] & 1) == 0) n++;
                    int rs = n + 2;
                    if (rs < dataEnd) {
                        List<PbField> fs = pbReadFields(Arrays.copyOfRange(raw, rs, dataEnd));
                        int ch = 0; long m = 0; byte[] p = new byte[0];
                        for (PbField f : fs) {
                            if (f.num == 2) ch = (int) f.v;
                            else if (f.num == 4) m = f.v;
                            else if (f.num == 5 && f.b != null) p = f.b;
                        }
                        if (m != 0) out.add(new PbPkt(ch, m, p));
                    }
                }
            }
            off = end;
        }
        return new byte[0];
    }

    // ================= 8. JBL BLE GATT =================
    static final class JblPkt {
        final int cmd; final byte[] payload;
        JblPkt(int c, byte[] p) { cmd = c; payload = p; }
    }

    static byte[] jblBuild(int cmd, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] out = new byte[3 + len];
        out[0] = (byte) 0xAA; out[1] = (byte) cmd; out[2] = (byte) len;
        if (len > 0) System.arraycopy(payload, 0, out, 3, len);
        return out;
    }

    static byte[] jblAncSet(byte mode) {
        byte anc = mode == Protocol.ANC_ON ? (byte) 1 : 0;
        byte amb = mode == Protocol.ANC_TRANSPARENCY ? (byte) 1 : 0;
        return jblBuild(0x91, new byte[]{0x10, 0x01, anc, 0x02, amb, 0x03, 0x00});
    }

    static byte[] jblEqSet(byte eqId) {
        byte c = eqId == 0 ? 0x00 : (eqId == 2 ? 0x02 : (eqId == 3 ? 0x07 : (byte) 0x03));
        return jblBuild(0x40, new byte[]{c});
    }

    static byte[] jblParseStream(byte[] buf, List<JblPkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if ((buf[off] & 0xFF) != 0xAA) { off++; continue; }
            if (off + 3 > total) break;
            int cmd = buf[off + 1] & 0xFF, len = buf[off + 2] & 0xFF;
            if (off + 3 + len > total) break;
            byte[] p = len > 0 ? Arrays.copyOfRange(buf, off + 3, off + 3 + len) : new byte[0];
            out.add(new JblPkt(cmd, p));
            off += 3 + len;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }
}
