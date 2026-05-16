package com.android.tine.enhances;

import android.util.Log;

import com.android.tine.Tine;
import com.android.tine.TineConfig;
import com.android.tine.utils.Primitives;

/**
 * @author canyie
 */
public final class TineEnhances {
    public static final String TAG = "TineEnhances";
    /**
     * A function object that will be invoked to load our native library (libtine-enhances.so)
     * @see Tine.LibLoader
     */
    public static Tine.LibLoader libLoader = new Tine.LibLoader() {
        @Override public void loadLib() {
            System.loadLibrary("tine-enhances");
        }
    };
    private static volatile boolean inited;

    /**
     * Initialize the Tine enhances library if uninitialized.
     */
    public static void ensureInited() {
        if (inited) return;
        synchronized (TineEnhances.class) {
            if (inited) return;
            if (libLoader != null) libLoader.loadLib();
            inited = true;
        }
    }

    /**
     * Enable delay hook (aka pending hook) for subsequent hooks.
     * Allow hooking a static method without pre-initialize its declaring class.
     * @return Whether the delay hook successfully enabled.
     */
    public static boolean enableDelayHook() {
        ensureInited();
        if (!PendingHookHandler.canWork()) {
            Log.e(TAG, "PendingHookHandler not working");
            return false;
        }
        PendingHookHandler.install().setEnabled(true);
        return true;
    }

    public static void logD(String fmt, Object... args) {
        if (TineConfig.debug)
            Log.d(TAG, String.format(fmt, args));
    }

    public static void logE(String msg, Throwable e) {
        Log.e(TAG, msg, e);
    }

    public static void logE(String msg) {
        Log.e(TAG, msg);
    }

    /** Called by JNI, do NOT remove */
    private static void onClassInit(long ptr) {
        try {
            Class<?> cls = (Class<?>) Tine.getObject(Primitives.currentArtThread(), ptr);
            ClassInitMonitor.getCallback().onClassInit(cls);
        } catch (Throwable e) {
            TineEnhances.logE("Unexpected exception threw in onClassInit", e);
        }
    }

    static native boolean initClassInitMonitor(int sdkLevel, long openElf, long findElfSymbol,
                                               long closeElf, long getMethodDeclaringClass,
                                               long syncMethodEntry, long suspendVM, long resumeVM);
    static native void careClassInit(long ptr);
    public static native void recordMethodHooked(long target, long entrypoint, long backup);
}
