package com.android.tine.examples.test;

import android.content.Context;
import android.widget.Toast;

import com.android.tine.enhances.PendingHookHandler;
import com.android.tine.enhances.TineEnhances;
import com.android.tine.examples.ExampleApp;

/**
 * @author canyie
 */
public class DelayHookTest extends Test {
    private boolean enabled;
    @Override public int run() {
        int res = IGNORED;
        Context ctx = ExampleApp.getInstance();
        CharSequence alert;
        if (enabled) {
            PendingHookHandler h = PendingHookHandler.instance();
            if (h != null)
                h.setEnabled(false);
            enabled = false;
            alert = "Disabled delay hook";
        } else {
            if (TineEnhances.enableDelayHook()) {
                enabled = true;
                alert = "Enabled delay hook";
            } else {
                alert = "Delay hook init error";
                res = FAILED;
            }
        }
        Toast.makeText(ctx, alert, Toast.LENGTH_SHORT).show();
        return res;
    }

    @Override protected int testImpl() {
        throw new UnsupportedOperationException();
    }
}
