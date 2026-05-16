package com.android.tine.enhances;

import com.android.tine.Tine;
import com.android.tine.TineConfig;
import com.android.tine.utils.Primitives;

/**
 * @author canyie
 */
public class ClassInitMonitor {
    private static boolean canWork;
    private static Callback callback;

    static {
        try {
            Tine.ensureInitialized();
            canWork = TineEnhances.initClassInitMonitor(TineConfig.sdkLevel, Tine.openElf,
                    Tine.findElfSymbol, Tine.closeElf, Tine.getMethodDeclaringClass,
                    Tine.syncMethodEntry, Tine.suspendVM, Tine.resumeVM);
        } catch (Throwable e) {
            TineEnhances.logE("Error in initClassInitMonitor", e);
        }
    }

    public static boolean canWork() {
        return canWork;
    }

    public static Callback getCallback() {
        return callback;
    }

    public static Callback setCallback(Callback cb) {
        Callback origin = callback;
        callback = cb;
        return origin;
    }

    public static void care(Class<?> cls) {
        if (cls == null) throw new NullPointerException("cls == null");
        TineEnhances.careClassInit(Primitives.getAddress(cls));
    }

    public interface Callback {
        void onClassInit(Class<?> cls);
    }
}
