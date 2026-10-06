package com.melody.melodylink.hook;

import android.app.Application;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Owns shared-file naming so HookModule does not know the storage layout. */
final class MelodySharedStateStore {
    static final String STATE_FILE = ".melodylink_sony_state";
    static final String COMMAND_FILE = ".melodylink_sony_anc_command";
    static final String BATTERY_COMMAND_FILE = ".melodylink_sony_battery_command";
    static final String SETTING_COMMAND_FILE = ".melodylink_sony_setting_command";
    static final String BOSE_CNC_STATE_FILE = ".melodylink_bose_cnc_state";
    static final String BOSE_EXTRA_STATE_FILE = ".melodylink_bose_extra_state";
    static final String BOSE_EXTRA_COMMAND_FILE = ".melodylink_bose_extra_command";
    static final String BOSE_CNC_COMMAND_FILE = ".melodylink_bose_cnc_command";
    static final String BOSE_USER_DISCONNECT_FILE = ".melodylink_bose_user_disconnect";

    private final File directory;

    private MelodySharedStateStore(File directory) {
        this.directory = directory;
    }

    static MelodySharedStateStore from(Application application) {
        return application == null ? null : new MelodySharedStateStore(application.getFilesDir());
    }

    File stateFile() {
        return new File(directory, STATE_FILE);
    }

    File commandFile() {
        return new File(directory, COMMAND_FILE);
    }

    File batteryCommandFile() {
        return new File(directory, BATTERY_COMMAND_FILE);
    }

    File settingCommandFile() {
        return new File(directory, SETTING_COMMAND_FILE);
    }

    File boseCncStateFile() {
        return new File(directory, BOSE_CNC_STATE_FILE);
    }

    File boseExtraStateFile() {
        return new File(directory, BOSE_EXTRA_STATE_FILE);
    }

    File boseExtraCommandFile() {
        return new File(directory, BOSE_EXTRA_COMMAND_FILE);
    }

    File boseCncCommandFile() {
        return new File(directory, BOSE_CNC_COMMAND_FILE);
    }

    File boseUserDisconnectFile() {
        return new File(directory, BOSE_USER_DISCONNECT_FILE);
    }

    /**
     * Melody-level "user tapped 断开连接" latch. System-level profiles reconnect on
     * their own seconds later (TWS auto-reconnect), so no Bluetooth probe can
     * distinguish that from a genuine reconnect — the intent has to be latched here,
     * persisted, and cleared only when a reconnect arrives late enough (>=60s) to be
     * user-initiated (earbuds power-cycled) rather than the automatic one.
     */
    static boolean writeBoseUserDisconnect(File file, String address, boolean flag, long at) {
        return write(file, address + "\n" + (flag ? "1" : "0") + "\n" + at + "\n");
    }

    /** {flag, at} or null when absent/foreign-address. */
    static Object[] readBoseUserDisconnect(File file, String expectAddress) {
        String[] lines = readLines(file, 3);
        if (lines == null) return null;
        String address = lines[0].trim();
        if (address.isEmpty() || "null".equals(address)) return null;
        if (expectAddress != null && !expectAddress.equalsIgnoreCase(address)) return null;
        try {
            return new Object[]{"1".equals(lines[1].trim()),
                    Long.parseLong(lines[2].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static SharedState readState(File file) {
        String[] lines = readLines(file, 2);
        if (lines == null) return null;
        try {
            int modeIndex = lines.length >= 3 ? Integer.parseInt(lines[2].trim()) : -1;
            Boolean dsee = lines.length >= 4 ? parseBoolean(lines[3]) : null;
            Boolean pauseWhenRemoved = lines.length >= 5 ? parseBoolean(lines[4]) : null;
            return new SharedState(lines[0].trim(), Integer.parseInt(lines[1].trim()), modeIndex, dsee, pauseWhenRemoved);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static SharedCommand readCommand(File file) {
        String[] lines = readLines(file, 3);
        if (lines == null) return null;
        try {
            return new SharedCommand(lines[0].trim(), Integer.parseInt(lines[1].trim()), lines[2].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static SharedBatteryCommand readBatteryCommand(File file) {
        String[] lines = readLines(file, 2);
        return lines == null ? null : new SharedBatteryCommand(lines[0].trim(), lines[1].trim());
    }

    static SharedSettingCommand readSettingCommand(File file) {
        String[] lines = readLines(file, 4);
        if (lines == null) return null;
        String value = lines[2].trim();
        if (!"0".equals(value) && !"1".equals(value)) return null;
        return new SharedSettingCommand(lines[0].trim(), lines[1].trim(), "1".equals(value), lines[3].trim());
    }

    static boolean writeState(File file, String address, int ownerPid, int modeIndex,
            Boolean dsee, Boolean pauseWhenRemoved) {
        return write(file, address + "\n" + ownerPid + "\n" + modeIndex + "\n"
                + encodeBoolean(dsee) + "\n" + encodeBoolean(pauseWhenRemoved) + "\n");
    }

    static boolean writeCommand(File file, String address, int modeIndex, String nonce) {
        return write(file, address + "\n" + modeIndex + "\n" + nonce + "\n");
    }

    static boolean writeBatteryCommand(File file, String address, String nonce) {
        return write(file, address + "\n" + nonce + "\n");
    }

    static boolean writeSettingCommand(File file, String address, String settingId, boolean value, String nonce) {
        return write(file, address + "\n" + settingId + "\n" + (value ? "1" : "0") + "\n" + nonce + "\n");
    }

    /**
     * Bose settings state: address + confirmed CNC level (0..10).
     * Returns {0, level}. Older builds wrote a third wind-block line; it is
     * still read and ignored so an existing state file keeps working.
     */
    static boolean writeBoseCncState(File file, String address, int level) {
        return write(file, address + "\n" + level + "\n");
    }

    static int[] readBoseCncState(File file) {
        String[] lines = readLines(file, 2);
        if (lines == null) return null;
        try {
            return new int[]{0, Integer.parseInt(lines[1].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String readBoseCncAddress(File file) {
        String[] lines = readLines(file, 2);
        return lines == null ? null : lines[0].trim();
    }

    /** Bose command: address + requested level + nonce. */
    static boolean writeBoseCncCommand(File file, String address, int level, String nonce) {
        return write(file, address + "\n" + level + "\n" + nonce + "\n");
    }

    static SharedBoseCncCommand readBoseCncCommand(File file) {
        String[] lines = readLines(file, 3);
        if (lines == null) return null;
        try {
            return new SharedBoseCncCommand(lines[0].trim(),
                    Integer.parseInt(lines[1].trim()), lines[2].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean delete(File file) {
        return file == null || !file.exists() || file.delete();
    }

    private static boolean write(File file, String content) {
        if (file == null) return false;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String[] readLines(File file, int minimumLines) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128];
            int count = input.read(buffer);
            if (count <= 0) return null;
            String[] lines = new String(buffer, 0, count, StandardCharsets.US_ASCII).split("\\r?\\n");
            return lines.length >= minimumLines ? lines : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String encodeBoolean(Boolean value) {
        return value == null ? "-1" : value ? "1" : "0";
    }

    private static Boolean parseBoolean(String value) {
        String trimmed = value.trim();
        if ("1".equals(trimmed)) return true;
        if ("0".equals(trimmed)) return false;
        return null;
    }

    static final class SharedState {
        final String address;
        final int ownerPid;
        final int modeIndex;
        final Boolean dsee;
        final Boolean pauseWhenRemoved;

        SharedState(String address, int ownerPid, int modeIndex, Boolean dsee, Boolean pauseWhenRemoved) {
            this.address = address;
            this.ownerPid = ownerPid;
            this.modeIndex = modeIndex;
            this.dsee = dsee;
            this.pauseWhenRemoved = pauseWhenRemoved;
        }
    }

    static final class SharedCommand {
        final String address;
        final int modeIndex;
        final String nonce;

        SharedCommand(String address, int modeIndex, String nonce) {
            this.address = address;
            this.modeIndex = modeIndex;
            this.nonce = nonce;
        }
    }

    static final class SharedBatteryCommand {
        final String address;
        final String nonce;

        SharedBatteryCommand(String address, String nonce) {
            this.address = address;
            this.nonce = nonce;
        }
    }

    static final class SharedSettingCommand {
        final String address;
        final String settingId;
        final boolean value;
        final String nonce;

        SharedSettingCommand(String address, String settingId, boolean value, String nonce) {
            this.address = address;
            this.settingId = settingId;
            this.value = value;
            this.nonce = nonce;
        }
    }

    /**
     * EQ / button-remap / mode-slot state published by the primary process:
     * bass, mid, treble, then the three Action-button event slots
     * (single / long / double press), then one byte per custom mode slot 5-10
     * holding its CNC level (255 = unknown).
     */
    static boolean writeBoseExtraState(File file, String address, int[] values) {
        if (values == null) return false;
        StringBuilder out = new StringBuilder(address);
        for (int value : values) out.append('\n').append(value);
        out.append('\n');
        return write(file, out.toString());
    }

    static int[] readBoseExtraState(File file) {
        String[] lines = readLines(file, 16);
        if (lines == null) return null;
        int[] values = new int[lines.length - 1];
        for (int i = 1; i < lines.length; i++) {
            try {
                values[i - 1] = Integer.parseInt(lines[i].trim());
            } catch (NumberFormatException ignored) {
                values[i - 1] = -1;
            }
        }
        return values;
    }

    static String readBoseExtraAddress(File file) {
        String[] lines = readLines(file, 16);
        return lines == null ? null : lines[0].trim();
    }

    /**
     * One settings transaction forwarded from the :fg detail page to the primary
     * process, which owns the BMAP session. kind: 0=EQ band, 1=button, 2=mode slot.
     * For kind 0 the pair carries (bandId, value); for kind 1 (button, event) with
     * value=action; for kind 2 (slot, cnc) with extra1=spatial, extra2=wind.
     */
    static boolean writeBoseExtraCommand(File file, String address, int kind, int index,
            int value, int extra1, int extra2, String nonce) {
        return write(file, address + "\n" + kind + "\n" + index + "\n" + value + "\n"
                + extra1 + "\n" + extra2 + "\n" + nonce + "\n");
    }

    static SharedBoseExtraCommand readBoseExtraCommand(File file) {
        String[] lines = readLines(file, 7);
        if (lines == null) return null;
        try {
            return new SharedBoseExtraCommand(lines[0].trim(),
                    Integer.parseInt(lines[1].trim()), Integer.parseInt(lines[2].trim()),
                    Integer.parseInt(lines[3].trim()), Integer.parseInt(lines[4].trim()),
                    Integer.parseInt(lines[5].trim()), lines[6].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class SharedBoseExtraCommand {
        final String address;
        final int kind;
        final int index;
        final int value;
        final int extra1;
        final int extra2;
        final String nonce;

        SharedBoseExtraCommand(String address, int kind, int index, int value, int extra1,
                int extra2, String nonce) {
            this.address = address;
            this.kind = kind;
            this.index = index;
            this.value = value;
            this.extra1 = extra1;
            this.extra2 = extra2;
            this.nonce = nonce;
        }
    }

    static final class SharedBoseCncCommand {
        final String address;
        final int level;
        final String nonce;

        SharedBoseCncCommand(String address, int level, String nonce) {
            this.address = address;
            this.level = level;
            this.nonce = nonce;
        }
    }

}
