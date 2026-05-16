package com.android.tine.examples.test;

import android.util.Log;

import com.android.tine.Tine;
import com.android.tine.examples.ExampleApp;

/**
 * @author canyie
 */
public class Arg0Test extends Test {
    public Arg0Test() {
        super("target", (Class<?>[]) null);
    }

    @Override protected int testImpl() {
        return target();
    }

    @Override public void afterCall(Tine.CallFrame callFrame) throws Throwable {
        super.afterCall(callFrame);
        callFrame.setResultIfNoException(SUCCESS);
    }

    private static int target() {
        Log.i(ExampleApp.TAG, "Arg0Test.target()");
        return FAILED;
    }
}
