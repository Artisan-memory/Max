package tw.nekomimi.nekogram.voice;

import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;

/**
 * Wrapper around the voice_rt library. Only arm64-v8a and armeabi-v7a ship with it, so every entry
 * point reports failure rather than throwing and {@link VoiceChanger} keeps its own effects for
 * everything else.
 */
public class VoiceChangerNative {

    private static Boolean available;

    private long handle;

    static {
        try {
            System.loadLibrary("voice_rt");
        } catch (Throwable e) {
            FileLog.e("voice changer: native library not present", e);
        }
    }

    public static boolean isAvailable() {
        if (available == null) {
            try {
                available = nativeAvailable();
            } catch (Throwable e) {
                FileLog.e("voice changer: native bridge unusable", e);
                available = false;
            }
        }
        return available;
    }

    /**
     * @param latencyMs how much the library may buffer; calls want this small, a round video can
     *                  afford more in exchange for smoother output
     */
    public static VoiceChangerNative create(int sampleRate, float latencyMs, float[] params) {
        if (!isAvailable()) {
            return null;
        }
        long handle = nativeCreate(sampleRate, latencyMs);
        if (handle == 0) {
            return null;
        }
        VoiceChangerNative core = new VoiceChangerNative();
        core.handle = handle;
        nativeSetParams(handle, params, 0);
        return core;
    }

    public void process(ByteBuffer buffer, int lengthBytes) {
        if (handle != 0 && buffer.isDirect()) {
            nativeProcess(handle, buffer, lengthBytes / 2);
        }
    }

    public void release() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native boolean nativeAvailable();

    private static native long nativeCreate(int sampleRate, float latencyMs);

    private static native void nativeDestroy(long handle);

    private static native void nativeReset(long handle);

    private static native int nativeLatencyFrames(long handle);

    private static native void nativeSetParams(long handle, float[] values, int bypass);

    private static native int nativeProcess(long handle, ByteBuffer buffer, int sampleCount);
}
