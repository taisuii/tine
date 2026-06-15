package com.android.tine.examples;

import android.os.Looper;
import org.junit.Test;
import com.android.tine.examples.test.*;

import static com.android.tine.examples.ExampleApp.ALL_TESTS;
import static com.android.tine.examples.ExampleApp.GC_TEST;
import static com.android.tine.examples.ExampleApp.TOAST_TEST;
import static com.android.tine.examples.ExampleApp.TOGGLE_DELAY_HOOK_TEST;
import static com.android.tine.examples.test.Test.*;
import static org.junit.Assert.*;

/**
 * @author canyie
 */
public class AutomatedTest {
    // We run ToggleDelayHookTest, ToastHookTest and GCTest manually
    private static final TestItem[] TESTS = new TestItem[ALL_TESTS.length - 3];

    static {
        System.arraycopy(ALL_TESTS, 1, TESTS, 0, TESTS.length);
    }

    private void runTests(int step) {
        for (TestItem i : TESTS) {
            assertEquals("Step " + step+ ": " + i.name, SUCCESS, i.test.run());
        }
        assertEquals("Step " + step+ ": " + TOAST_TEST.name, IGNORED, TOAST_TEST.run());
    }

    @Test public void run() {
        // Prepare Looper to allow testing Toast
        Looper.prepare();

        // Step 1: Test if we can hook methods, parse arguments and change return value with delay hook
        assertEquals("Step 1: Enable delay hook", IGNORED, TOGGLE_DELAY_HOOK_TEST.run());
        runTests(1);

        // Step 2: Disable delay hook and re-execute all tests
        assertEquals("Step 2: Disable delay hook", IGNORED, TOGGLE_DELAY_HOOK_TEST.run());
        runTests(2);

        // Step 3: Check if our hook is still alive after GC x3
        for (int i = 0;i < 3;i++)
            assertEquals("GC " + GC_TEST.name, IGNORED, GC_TEST.run());

        runTests(3);

        // Step 4: Check if our hook is still alive with delay hook enabled after GC x3
        assertEquals("Step 4: Enable delay hook", IGNORED, TOGGLE_DELAY_HOOK_TEST.run());
        for (int i = 0;i < 3;i++)
            assertEquals("GC " + GC_TEST.name, IGNORED, GC_TEST.run());

        runTests(4);

        // Step 5: Check if we can properly handle a moving GC while hooked methods are executing.
        // Previously this crashed on backup method invoking: a moving GC could relocate the
        // declaring Class while the backup method (whose declaring_class is a raw reference) was
        // being invoked, leaving a dangling pointer. With the moving-GC guard around backup calls
        // (Tine.beginCallBackup/endCallBackup) this must run without crashing.
        runTestsUnderGcPressure(5);
    }

    private void runTestsUnderGcPressure(int step) {
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread gcStress = new Thread(() -> {
            while (!stop.get()) {
                // Allocate garbage and force GC to maximize the chance of relocating Class objects
                // while the main thread is inside a backup method invocation.
                byte[][] garbage = new byte[256][];
                for (int i = 0; i < garbage.length; i++) garbage[i] = new byte[4096];
                Runtime.getRuntime().gc();
            }
        }, "tine-gc-stress");
        gcStress.setDaemon(true);
        gcStress.start();

        try {
            // Repeatedly exercise the hook -> invokeOriginalMethod -> backup-call paths under pressure.
            for (int i = 0; i < 50; i++) {
                runTests(step);
            }
        } finally {
            stop.set(true);
            try {
                gcStress.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
