package uwu.realness.gitveu.tiny;

import java.util.Arrays;
import java.util.List;

final class GalaxyProto {
    static final byte SOM_MOD = (byte) 0xFD, EOM_MOD = (byte) 0xDD;
    static final byte SOM_LEG = (byte) 0xFE, EOM_LEG = (byte) 0xEE;

    static final int MSG_STATUS = 0x60;
    static final int MSG_EXT_STATUS = 0x61;
    static final int MSG_NC_UPD = 0x77, MSG_NC_SET = 0x78;
    static final int MSG_AMB_SET = 0x80, MSG_AMB_UPD = 0x81;
    static final int MSG_EQ_SET = 0x86;
    static final int MSG_MGR_INFO = 0x88;
    static final int MSG_NR_SET = 0x98, MSG_NR_UPD = 0x9B;
    static final int MSG_SEAMLESS_SET = 0xAF;

    static final class Pkt {
        final int msgId;
        final byte[] payload;
        Pkt(int msgId, byte[] payload) { this.msgId = msgId; this.payload = payload; }
    }

    static int crc16Ccitt(byte[] d) {
        int crc = 0;
        for (byte b : d) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    static byte[] build(int msgId, byte[] payload, boolean legacy) {
        int pLen = payload == null ? 0 : payload.length;
        int size = 1 + pLen + 2;
        byte[] crcBuf = new byte[1 + pLen];
        crcBuf[0] = (byte) (msgId & 0xFF);
        if (pLen > 0) System.arraycopy(payload, 0, crcBuf, 1, pLen);
        int crc = crc16Ccitt(crcBuf);
        byte[] out = new byte[4 + size];
        if (legacy) {
            out[0] = SOM_LEG;
            out[1] = 0;
            out[2] = (byte) (size & 0xFF);
        } else {
            out[0] = SOM_MOD;
            out[1] = (byte) (size & 0xFF);
            out[2] = (byte) ((size >>> 8) & 0x03);
        }
        out[3] = (byte) (msgId & 0xFF);
        if (pLen > 0) System.arraycopy(payload, 0, out, 4, pLen);
        int cOff = 4 + pLen;
        out[cOff] = (byte) (crc & 0xFF);
        out[cOff + 1] = (byte) ((crc >>> 8) & 0xFF);
        out[cOff + 2] = legacy ? EOM_LEG : EOM_MOD;
        return out;
    }

    static byte[] writeAnc(byte realmeMode, int model) {
        boolean leg = model == Protocol.MODEL_GALAXY_BUDS;
        if (model == Protocol.MODEL_GALAXY_LIVE) {
            return build(MSG_NR_SET, new byte[]{realmeMode == Protocol.ANC_ON ? (byte) 1 : (byte) 0}, false);
        }
        if (model == Protocol.MODEL_GALAXY_BUDS || model == Protocol.MODEL_GALAXY_PLUS) {
            return build(MSG_AMB_SET, new byte[]{realmeMode == Protocol.ANC_TRANSPARENCY ? (byte) 1 : (byte) 0}, leg);
        }
        byte v = realmeMode == Protocol.ANC_ON ? (byte) 1 : (realmeMode == Protocol.ANC_TRANSPARENCY ? (byte) 2 : (byte) 0);
        return build(MSG_NC_SET, new byte[]{v}, false);
    }

    static byte[] writeEq(byte realmeEqId, boolean legacy) {
        int c = realmeEqId == 0 ? 0 : (realmeEqId == 1 ? 1 : (realmeEqId == 3 ? 3 : 4));
        if (legacy) {
            return build(MSG_EQ_SET, c == 0 ? new byte[]{0, 5} : new byte[]{1, (byte) (c + 4)}, true);
        }
        return build(MSG_EQ_SET, new byte[]{(byte) c}, false);
    }

    static byte[] parseStream(byte[] buf, List<Pkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            byte b0 = buf[off];
            if (b0 != SOM_MOD && b0 != SOM_LEG) { off++; continue; }
            if (off + 4 > total) break;
            boolean leg = (b0 == SOM_LEG);
            int size = leg ? (buf[off + 2] & 0xFF) : ((buf[off + 1] & 0xFF) | ((buf[off + 2] & 0x03) << 8));
            if (size < 3 || size > 1024) { off++; continue; }
            int full = 4 + size;
            if (off + full > total) break;
            if (buf[off + full - 1] != (leg ? EOM_LEG : EOM_MOD)) { off++; continue; }
            int id = buf[off + 3] & 0xFF;
            int pLen = size - 3;
            out.add(new Pkt(id, pLen > 0 ? Arrays.copyOfRange(buf, off + 4, off + 4 + pLen) : new byte[0]));
            off += full;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }
}
