package com.android.tine.examples.test;

import android.util.Log;

import com.android.tine.Tine;
import com.android.tine.examples.ExampleApp;

/**
 * @author canyie
 */
public class NonStaticTest extends Test {
    public NonStaticTest() {
        super("target", (Class<?>[]) null);
    }

    @Override protected int testImpl() {
        return target() ? SUCCESS : FAILED;
    }

    public boolean target() {
        Log.i(ExampleApp.TAG, "NonStaticTest.target()");
        return false;
    }

    @Override public void afterCall(Tine.CallFrame callFrame) throws Throwable {
        super.afterCall(callFrame);
        callFrame.setResultIfNoException(true);
    }
}
