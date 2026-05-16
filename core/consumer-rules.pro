# Tine
-keep class com.android.tine.Tine {
    public static long openElf;
    public static long findElfSymbol;
    public static long closeElf;
    public static long getMethodDeclaringClass;
    public static long syncMethodEntry;
    public static long suspendVM;
    public static long resumeVM;
    private static int arch;
}
-keep class com.android.tine.Tine$HookRecord {
    public long trampoline;
}
-keep class com.android.tine.Ruler { *; }
-keep class com.android.tine.Ruler$I { *; }
-keep class com.android.tine.entry.**Entry {
    static *** **Bridge(...);
}

# Prevent R8 from removing "unused" library native methods while they're still being used
-keep class * {
    native <methods>;
}
