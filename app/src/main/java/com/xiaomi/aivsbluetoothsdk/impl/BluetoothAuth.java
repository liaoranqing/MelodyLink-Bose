package com.xiaomi.aivsbluetoothsdk.impl;

/** JNI signatures registered by the bundled Xiaomi authentication library. */
public final class BluetoothAuth {
    static {
        System.loadLibrary("xm_bluetooth");
    }

    private BluetoothAuth() {
    }

    public static native boolean nativeInit();
    public static native byte[] getRandomAuthData();
    public static native byte[] getRandomAuthCheckData();
    public static native int setLinkKey(byte[] key);
    public static native byte[] getEncryptedAuthData(byte[] input);
    public static native byte[] getEncryptedAuthCheckData(byte[] input);
}
