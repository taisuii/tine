package com.android.tine.examples.test;

import com.android.tine.Tine;

/**
 * @author canyie
 */
public class ThrowExceptionTest extends Test {
    public ThrowExceptionTest() {
        super("target", (Class<?>[]) null);
    }

    @Override protected int testImpl() {
        try {
            target();
            return FAILED;
        } catch (MyEx e) {
            return e.b ? SUCCESS : FAILED;
        }
    }

    private static void target() throws MyEx {
        throw new MyEx();
    }

    @Override public void afterCall(Tine.CallFrame callFrame) throws Throwable {
        super.afterCall(callFrame);
        MyEx e = (MyEx) callFrame.getThrowable();
        e.b = true;
    }

    static class MyEx extends Exception {
        boolean b = false;
    }
}
