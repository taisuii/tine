package com.android.tine.callback;

import com.android.tine.Tine;

import java.lang.reflect.Member;

/**
 * Interface definition for a callback that will be invoked before/after the method executes.
 * @author canyie
 * @see Tine#hook(Member, MethodHook)
 */
public abstract class MethodHook {
    /**
     * Invoked before the method gets called. You can get or modify some info about this call by the given {@code callFrame}.
     * Note that setting result or exception will prevent the original method call, and next hooks won't
     * be called. Throwing any exception in this method will cause the result or exception you set
     * gets reset, if you want to set an exception as the method thrown, use {@link com.android.tine.Tine.CallFrame#setThrowable(Throwable)}
     * @param callFrame object that stores some info about info call.
     */
    public void beforeCall(Tine.CallFrame callFrame) throws Throwable {
    }

    /**
     * Invoked after the method gets called. You can get or modify some info about this call by the given {@code callFrame}.
     * Throwing any exception in this method will cause the result or exception you set gets reset.
     * If you want to set an exception as the method thrown, use {@link com.android.tine.Tine.CallFrame#setThrowable(Throwable)}
     * @param callFrame object that stores some info about info call.
     */
    public void afterCall(Tine.CallFrame callFrame) throws Throwable {
    }

    public class Unhook {
        private final Tine.HookRecord hookRecord;

        public Unhook(Tine.HookRecord hookRecord) {
            this.hookRecord = hookRecord;
        }

        public Member getTarget() {
            return hookRecord.target;
        }

        public MethodHook getCallback() {
            return MethodHook.this;
        }

        public void unhook() {
            Tine.getHookHandler().handleUnhook(hookRecord, MethodHook.this);
        }
    }
}
