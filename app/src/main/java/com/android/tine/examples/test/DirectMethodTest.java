package com.android.tine.examples.test;

import android.util.Log;

import com.android.tine.Tine;
import com.android.tine.examples.ExampleApp;

/**
 * @author canyie
 */
public class DirectMethodTest extends Test {
    public DirectMethodTest() {
        super("target", (Class<?>[]) null);
    }

    @Override protected int testImpl() {
        return target() ? SUCCESS : FAILED;
    }

    /* private methods are direct method */
    private boolean target() {
        Log.i(ExampleApp.TAG, "DirectMethodTest.target()");
        return false;
    }

    @Override public void afterCall(Tine.CallFrame callFrame) throws Throwable {
        super.afterCall(callFrame);
        callFrame.setResultIfNoException(true);
    }
}
