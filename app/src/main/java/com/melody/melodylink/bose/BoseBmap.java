package com.melody.melodylink.bose;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure BMAP packet codec; no Bluetooth or Android dependencies.
 * Ported from the verified Bose Melody Control 1.7.x implementation.
 */
public final class BoseBmap {
    public static final int OP_SET = 0;
    public static final int OP_GET = 1;
    public static final int OP_SETGET = 2;
    public static final int OP_STATUS = 3;
    public static final int OP_ERROR = 4;
    public static final int OP_START = 5;
    public static final int OP_RESULT = 6;
    public static final int OP_PROCESSING = 7;

    public static final int BLOCK_BATTERY = 2;
    public static final int FUNC_BATTERY = 2;
    public static final int BLOCK_AUDIO_MODES = 31;
    public static final int FUNC_CURRENT_MODE = 3;
    public static final int FUNC_AUDIO_SETTINGS = 10;

    /** BMAP RFCOMM service UUID used by Bose head products. */
    public static final String BMAP_UUID = "00000000-deca-fade-deca-deafdecacaff";

    private BoseBmap() {
    }

    public static byte[] packet(int block, int function, int operator, byte[] payload) {
        int length = payload == null ? 0 : payload.length;
        if (length > 255) throw new IllegalArgumentException("BMAP payload too large");
        byte[] frame = new byte[4 + length];
        frame[0] = (byte) block;
        frame[1] = (byte) function;
        frame[2] = (byte) (operator & 0x0f);
        frame[3] = (byte) length;
        if (payload != null) System.arraycopy(payload, 0, frame, 4, length);
        return frame;
    }

    public static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder out = new StringBuilder(data.length * 3);
        for (byte value : data) {
            if (out.length() > 0) out.append(' ');
            int v = value & 0xff;
            out.append(Character.forDigit(v >>> 4, 16));
            out.append(Character.forDigit(v & 0x0f, 16));
        }
        return out.toString();
    }

    public static final class Frame {
        public final int block;
        public final int function;
        public final int operator;
        public final byte[] payload;

        Frame(int block, int function, int operator, byte[] payload) {
            this.block = block;
            this.function = function;
            this.operator = operator;
            this.payload = payload;
        }

        public boolean matches(int wantedBlock, int wantedFunction) {
            return block == wantedBlock && function == wantedFunction;
        }

        public int u8(int index) {
            return payload[index] & 0xff;
        }
    }

    /** Incremental parser for concatenated BMAP frames and Bluetooth chunks. */
    public static final class Parser {
        private byte[] buffer = new byte[512];
        private int size;

        public void feed(byte[] data, int offset, int count, Sink sink) {
            if (data == null || count <= 0) return;
            ensure(size + count);
            System.arraycopy(data, offset, buffer, size, count);
            size += count;
            int position = 0;
            while (size - position >= 4) {
                int length = buffer[position + 3] & 0xff;
                int frameLength = 4 + length;
                if (frameLength > 259) {
                    position++;
                    continue;
                }
                if (size - position < frameLength) break;
                byte[] raw = new byte[frameLength];
                System.arraycopy(buffer, position, raw, 0, frameLength);
                position += frameLength;
                int operator = raw[2] & 0x0f;
                byte[] payload = new byte[length];
                System.arraycopy(raw, 4, payload, 0, length);
                sink.onFrame(new Frame(raw[0] & 0xff, raw[1] & 0xff,
                        operator, payload));
            }
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, size - position);
                size -= position;
            }
        }

        public void feed(byte[] data, Sink sink) {
            feed(data, 0, data.length, sink);
        }

        private void ensure(int wanted) {
            if (wanted > 64 * 1024) throw new IllegalArgumentException("BMAP stream buffer limit exceeded");
            if (wanted <= buffer.length) return;
            int capacity = buffer.length;
            while (capacity < wanted) capacity *= 2;
            byte[] grown = new byte[capacity];
            System.arraycopy(buffer, 0, grown, 0, size);
            buffer = grown;
        }
    }

    public interface Sink {
        void onFrame(Frame frame);
    }

    /** Convenience: decode every complete frame in one chunk. */
    public static List<Frame> decode(byte[] chunk) {
        List<Frame> frames = new ArrayList<>();
        new Parser().feed(chunk, frames::add);
        return frames;
    }
}
