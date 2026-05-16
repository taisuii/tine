package com.android.tine.examples.test;

import android.util.Log;

import com.android.tine.Tine;
import com.android.tine.examples.ExampleApp;

/**
 * @author canyie
 */
public class NotInitedTest extends Test {
    public NotInitedTest() {
        super(I.class, "target", int.class);
    }

    @Override protected int testImpl() {
        return I.target(114514);
    }

    @Override public void beforeCall(Tine.CallFrame callFrame) throws Throwable {
        super.beforeCall(callFrame);
        callFrame.args[0] = 1919810;
    }

    private static class I {
        static {
            Log.i(ExampleApp.TAG, "NotInitedTest initializing", new Throwable());
        }
        static int target(int i) {
            return i == 1919810 ? 1 : -1;
        }
    }
}
