package uwu.realness.gitveu.tiny;

import java.util.Arrays;
import java.util.List;

final class NothingProto {
    static final byte SOF = 0x55;
    static final int CMD_HANDSHAKE_REQ = 0xC006, CMD_HANDSHAKE_RSP = 0x4006;
    static final int CMD_BAT_REQ = 0xC007, CMD_BAT_RSP = 0x4007, CMD_BAT_EV = 0xE001;
    static final int CMD_INEAR_REQ = 0xC00E, CMD_INEAR_RSP = 0x400E, CMD_INEAR_SET = 0xF004;
    static final int CMD_ANC_REQ = 0xC01E, CMD_ANC_RSP = 0x401E, CMD_ANC_EV = 0xE003, CMD_ANC_EV_C = 0x6003, CMD_ANC_SET = 0xF00F;
    static final int CMD_EQ_C_REQ = 0xC01F, CMD_EQ_C_RSP = 0x401F, CMD_EQ_C_SET = 0xF010;
    static final int CMD_EQ_L_REQ = 0xC050, CMD_EQ_L_RSP = 0x4050, CMD_EQ_L_SET = 0xF01D;
    static final int CMD_LAT_REQ = 0xC041, CMD_LAT_RSP = 0x4041, CMD_LAT_SET = 0xF040;
    static final int CMD_BASS_REQ = 0xC04E, CMD_BASS_RSP = 0x404E, CMD_BASS_SET = 0xF051;
    static final int CMD_SPATIAL_REQ = 0xC04F, CMD_SPATIAL_RSP = 0x404F, CMD_SPATIAL_SET = 0xF052;

    private static int opId = 1;

    static final class Pkt {
        final int cmd;
        final byte[] payload;
        Pkt(int cmd, byte[] payload) { this.cmd = cmd; this.payload = payload; }
    }

    static int crc16Modbus(byte[] d, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (d[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : (crc >>> 1);
            }
        }
        return crc & 0xFFFF;
    }

    static byte[] build(int cmd, byte[] payload) {
        int pLen = payload == null ? 0 : payload.length;
        int bodyLen = 8 + pLen;
        byte[] out = new byte[bodyLen + 2];
        out[0] = SOF;
        out[1] = 0x60;
        out[2] = 0x01;
        out[3] = (byte) (cmd & 0xFF);
        out[4] = (byte) ((cmd >>> 8) & 0xFF);
        out[5] = (byte) (pLen & 0xFF);
        out[6] = (byte) ((pLen >>> 8) & 0xFF);
        out[7] = (byte) (opId & 0xFF);
        opId = opId >= 250 ? 1 : opId + 1;
        if (pLen > 0) System.arraycopy(payload, 0, out, 8, pLen);
        int crc = crc16Modbus(out, bodyLen);
        out[bodyLen] = (byte) (crc & 0xFF);
        out[bodyLen + 1] = (byte) ((crc >>> 8) & 0xFF);
        return out;
    }

    static byte ancLevelToCode(int hwLvl) {
        if (hwLvl == 1) return 3; // Comfort -> Low
        if (hwLvl == 0) return 2; // General -> Mid
        if (hwLvl == 3) return 4; // Dynamic -> Adaptive
        return 1;                 // Ultra -> High
    }

    static byte[] writeAncMode(byte realmeMode, int curLvl) {
        byte code = realmeMode == Protocol.ANC_OFF ? (byte) 5
            : (realmeMode == Protocol.ANC_TRANSPARENCY ? (byte) 7 : ancLevelToCode(curLvl));
        return build(CMD_ANC_SET, new byte[]{0x01, code, 0x00});
    }

    static byte[] writeAncLevel(byte hwLvl) {
        return build(CMD_ANC_SET, new byte[]{0x01, ancLevelToCode(hwLvl & 0xFF), 0x00});
    }

    static byte[] writeEq(byte realmeEqId, boolean listening) {
        byte id;
        if (listening) {
            id = realmeEqId == 0 ? (byte) 0 : (realmeEqId == 2 ? (byte) 4 : (realmeEqId == 3 ? (byte) 2 : (byte) 1));
        } else {
            id = realmeEqId == 0 ? (byte) 0 : (realmeEqId == 2 ? (byte) 1 : (realmeEqId == 3 ? (byte) 2 : (byte) 3));
        }
        return build(listening ? CMD_EQ_L_SET : CMD_EQ_C_SET, new byte[]{id, 0x00});
    }

    static byte[] writeBassBoost(boolean on, int level) {
        int clamped = Math.max(1, Math.min(5, level));
        return build(CMD_BASS_SET, new byte[]{(byte) (on ? 1 : 0), (byte) (clamped * 2)});
    }

    static byte[] parseStream(byte[] buf, List<Pkt> out) {
        int off = 0, total = buf.length;
        while (off < total) {
            if (buf[off] != SOF) { off++; continue; }
            if (off + 8 > total) break;
            int flags = (buf[off + 1] & 0xFF) | ((buf[off + 2] & 0xFF) << 8);
            int cmd = (buf[off + 3] & 0xFF) | ((buf[off + 4] & 0xFF) << 8);
            int pLen = (buf[off + 5] & 0xFF) | ((buf[off + 6] & 0xFF) << 8);
            if (pLen > 1024) { off++; continue; }
            int full = 8 + pLen + ((flags & 0x0020) != 0 ? 2 : 0);
            if (off + full > total) break;
            out.add(new Pkt(cmd, pLen > 0 ? Arrays.copyOfRange(buf, off + 8, off + 8 + pLen) : new byte[0]));
            off += full;
        }
        return off < total ? Arrays.copyOfRange(buf, off, total) : new byte[0];
    }
}
