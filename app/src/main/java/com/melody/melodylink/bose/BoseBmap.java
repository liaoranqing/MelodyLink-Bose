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
    public static final int FUNC_MODE_CONFIG = 6;
    public static final int FUNC_FAVORITES = 8;

    /** Block 1 "DeviceSettings": EQ, button remap, voice prompts. */
    public static final int BLOCK_DEVICE_SETTINGS = 1;
    public static final int FUNC_EQ = 7;
    public static final int FUNC_BUTTONS = 9;

    /** 3-band EQ band ids, as reported by [1.7]. */
    public static final int EQ_BAND_BASS = 0;
    public static final int EQ_BAND_MID = 1;
    public static final int EQ_BAND_TREBLE = 2;

    /**
     * Button ids from bosectl's constants table. 16 is the earbud's single
     * "Action" button; 0/2 are the left/right in-ear stems on some models.
     */
    public static final int BUTTON_ACTION = 16;
    public static final int BUTTON_LEFT_SHORTCUT = 4;
    public static final int BUTTON_RIGHT_SHORTCUT = 3;
    public static final int BUTTON_DISTAL_CNC = 0;
    public static final int BUTTON_VPA = 2;

    /** Event types accepted by [1.9]. */
    public static final int EVENT_SINGLE_PRESS = 4;
    public static final int EVENT_LONG_PRESS = 9;
    public static final int EVENT_DOUBLE_PRESS = 6;

    /** Action modes accepted by [1.9] (bosectl constants.py ACTION_MODES). */
    public static final int ACTION_VPA = 1;
    public static final int ACTION_ANC = 2;
    public static final int ACTION_BATTERY_LEVEL = 3;
    public static final int ACTION_PLAY_PAUSE = 4;
    public static final int ACTION_INCREASE_CNC = 5;
    public static final int ACTION_DECREASE_CNC = 6;
    public static final int ACTION_TOGGLE_WAKE_WORD = 7;
    public static final int ACTION_SWITCH_DEVICE = 8;
    public static final int ACTION_CONVERSATION_MODE = 9;
    public static final int ACTION_TRACK_FORWARD = 10;
    public static final int ACTION_TRACK_BACK = 11;
    public static final int ACTION_FETCH_NOTIFICATIONS = 12;
    public static final int ACTION_WIND_MODE = 13;
    public static final int ACTION_DISABLED = 14;
    public static final int ACTION_CLIENT_INTERACTION = 15;
    public static final int ACTION_SPOTIFY_GO = 16;
    public static final int ACTION_MODES_CAROUSEL = 17;
    public static final int ACTION_SPATIAL_AUDIO = 19;
    public static final int ACTION_LINE_IN_SWITCH = 20;
    public static final int ACTION_LINKING = 21;

    /** First user-writable mode slot; 0-4 are firmware presets. */
    public static final int MODE_SLOT_FIRST = 5;
    public static final int MODE_SLOT_LAST = 10;

    /** Block 1 Settings / block 7 Control, from bozo's BMAP reference. */
    public static final int FUNC_STANDBY_TIMER = 4;
    public static final int BLOCK_CONTROL = 7;
    public static final int FUNC_POWER = 4;

    /** AudioModes capabilities [31.2] feature-flag bits. */
    public static final int CAP_CNC = 1;
    public static final int CAP_AUTO_CNC = 1 << 1;
    public static final int CAP_SPATIAL = 1 << 2;
    public static final int CAP_WIND = 1 << 3;
    public static final int CAP_FAVORITES = 1 << 4;
    public static final int CAP_ANC = 1 << 5;

    /** ModeConfig STATUS mutability flags at offset 41 (bozo's BMAP.md). */
    public static final int MODE_FLAG_CNC = 1;
    public static final int MODE_FLAG_AUTO_CNC = 1 << 1;
    public static final int MODE_FLAG_SPATIAL = 1 << 2;
    public static final int MODE_FLAG_WIND = 1 << 3;
    public static final int MODE_FLAG_ANC = 1 << 4;

    /** Standby timer presets in minutes; 0 disables auto-off. */
    public static final int[] STANDBY_MINUTES = {0, 5, 10, 20, 30, 60, 120};

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

    /** [1.7] single EQ band SETGET payload: [value, bandId]. */
    public static byte[] eqBandPayload(int value, int bandId) {
        return new byte[]{(byte) (value & 0xff), (byte) (bandId & 0xff)};
    }

    /** [1.9] button remap SETGET payload: [buttonId, eventType, actionMode]. */
    public static byte[] buttonPayload(int buttonId, int eventType, int actionMode) {
        return new byte[]{(byte) (buttonId & 0xff), (byte) (eventType & 0xff),
                (byte) (actionMode & 0xff)};
    }

    /**
     * [31.6] ModeConfig SETGET payload (40 bytes, QC Ultra 2 / edith layout):
     * modeIndex, promptB1, promptB2, 32-byte name, cnc, autoCnc, spatial,
     * windBlock, ancToggle.
     */
    public static byte[] modeConfigPayload(int slot, String name, int cncLevel,
            int spatial, int windBlock, int ancToggle) {
        byte[] payload = new byte[40];
        payload[0] = (byte) (slot & 0xff);
        payload[1] = 0;
        payload[2] = 0;
        // 32-byte UTF-8 name, null padded (firmware field width).
        byte[] raw = name == null ? new byte[0] : name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int end = Math.min(raw.length, 31);
        System.arraycopy(raw, 0, payload, 3, end);
        payload[35] = (byte) (cncLevel & 0xff);
        payload[36] = 0;                       // autoCnc: firmware rejects 1
        payload[37] = (byte) (spatial & 0xff);
        payload[38] = (byte) (windBlock & 0xff);
        payload[39] = (byte) (ancToggle & 0xff);
        return payload;
    }

    /** [1.4] standby timer SETGET payload: [minutes]. */
    public static byte[] standbyTimerPayload(int minutes) {
        return new byte[]{(byte) (minutes & 0xff)};
    }

    /** [7.4] power START payload: 0 = off, 1 = on. */
    public static byte[] powerPayload(boolean on) {
        return new byte[]{(byte) (on ? 1 : 0)};
    }

    /** Human-readable label for a standby-timer value in minutes. */
    public static String standbyLabel(int minutes) {
        if (minutes <= 0) return "从不";
        if (minutes >= 60 && minutes % 60 == 0) return minutes / 60 + " 小时";
        return minutes + " 分钟";
    }

    /** Human-readable label for an action mode id. */
    public static String actionLabel(int action) {
        switch (action) {
            case 0: return "未配置";
            case ACTION_VPA: return "语音助手";
            case ACTION_ANC: return "切换降噪";
            case ACTION_BATTERY_LEVEL: return "播报电量";
            case ACTION_PLAY_PAUSE: return "播放/暂停";
            case ACTION_INCREASE_CNC: return "提高降噪强度";
            case ACTION_DECREASE_CNC: return "降低降噪强度";
            case ACTION_TOGGLE_WAKE_WORD: return "唤醒词";
            case ACTION_SWITCH_DEVICE: return "切换设备";
            case ACTION_CONVERSATION_MODE: return "对话模式";
            case ACTION_TRACK_FORWARD: return "下一曲";
            case ACTION_TRACK_BACK: return "上一曲";
            case ACTION_FETCH_NOTIFICATIONS: return "读取通知";
            case ACTION_WIND_MODE: return "风噪模式";
            case ACTION_DISABLED: return "禁用";
            case ACTION_CLIENT_INTERACTION: return "客户端交互";
            case ACTION_SPOTIFY_GO: return "Spotify";
            case ACTION_MODES_CAROUSEL: return "模式轮播";
            case ACTION_SPATIAL_AUDIO: return "空间音频";
            case ACTION_LINE_IN_SWITCH: return "切换音频源";
            case ACTION_LINKING: return "连接管理";
            default: return "动作" + action;
        }
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
