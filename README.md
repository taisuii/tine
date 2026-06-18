# Tine [![LICENSE](https://img.shields.io/badge/license-Anti%20996-blue.svg)](https://github.com/996icu/996.ICU/blob/master/LICENSE)

[中文版本](README_cn.md)
## Introduction
Tine is a dynamic java method hook framework on ART runtime, which can intercept almost all java method calls in the current process.

Currently it supports Android 4.4(ART only) ~ **15 Beta 4** with thumb-2/arm64 architecture.

About its working principle, you can refer to this Chinese [article](https://canyie.github.io/2020/04/27/dynamic-hooking-framework-on-art/).

Note: For Android 6.0 devices with arm32/thumb-2 architectures, the arguments may be wrong; and for Android 9.0+, Tine will disable the hidden api restriction policy.

~~The name, Tine, represents a class of antipsychotic drugs represented by Quetiapine and Clozapine. It is also an acronym for "Tine Is Not Epic".~~

## Usage
### Basic Usage
[![Download](https://img.shields.io/maven-central/v/com.android.tine/core.svg)](https://repo1.maven.org/maven2/com/android/tine/core/)

Add dependencies in build.gradle (like this):
```groovy
dependencies {
    implementation 'com.android.tine:core:<version>'
}
```
Basic configuration:
```java
TineConfig.debug = true; // Do we need to print more detailed logs?
TineConfig.debuggable = BuildConfig.DEBUG; // Is this process debuggable?
```

Example 1: monitor the creation of activities
```java
Tine.hook(Activity.class.getDeclaredMethod("onCreate", Bundle.class), new MethodHook() {
    @Override public void beforeCall(Tine.CallFrame callFrame) {
        Log.i(TAG, "Before " + callFrame.thisObject + " onCreate()");
    }

    @Override public void afterCall(Tine.CallFrame callFrame) {
        Log.i(TAG, "After " + callFrame.thisObject + " onCreate()");
    }
});
```

Example 2: monitor the creation and destroy of all java threads
```java
final MethodHook runHook = new MethodHook() {
    @Override public void beforeCall(Tine.CallFrame callFrame) throws Throwable {
        Log.i(TAG, "Thread " + callFrame.thisObject + " started...");
    }

    @Override public void afterCall(Tine.CallFrame callFrame) throws Throwable {
        Log.i(TAG, "Thread " + callFrame.thisObject + " exit...");
    }
};

Tine.hook(Thread.class.getDeclaredMethod("start"), new MethodHook() {
    @Override public void beforeCall(Tine.CallFrame callFrame) {
        Tine.hook(ReflectionHelper.getMethod(callFrame.thisObject.getClass(), "run"), runHook);
    }
});
```

Example 3: force allow any threads to modify ui:
```java
Method checkThread = Class.forName("android.view.ViewRootImpl").getDeclaredMethod("checkThread");
Tine.hook(checkThread, MethodReplacement.DO_NOTHING);
```

### Xposed Support
[![Download](https://img.shields.io/maven-central/v/com.android.tine/xposed.svg)](https://repo1.maven.org/maven2/com/android/tine/xposed/)

Tine supports hooking methods in Xposed-style and loading Xposed modules. (Only java method hooking is supported. Modules using unsupported features like Resource-hooking won't work.)
```groovy
implementation 'com.android.tine:xposed:<version>'
```
Directly hook methods in Xposed-style:
```java
TsHelpers.findAndHookMethod(TextView.class, "setText",
                CharSequence.class, TextView.BufferType.class, boolean.class, int.class,
                new TsMethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "Before TextView.setText");
                        param.args[0] = "hooked";
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "After TextView.setText");
                    }
                });
```
or like this:
```java
TsBridge.hookMethod(target, callback);
```

and you can load xposed modules (resources hook is not supported now):
```java
// 1. load modules
TineModule.loadModule(new File(modulePath));

// 2. call all 'ILoadPackageHook' callback
TineModule.onPackageLoad(packageName, processName, appInfo, isFirstApp, classLoader);
```
Note:
1. Hooks will only take effect in the current process. If you want hooks take effect in other processes, inject your code into them first. There's nothing to do with us.
2. Modules that use unsupported features (e.g. Resources hook or TsSharedPreferences) will not work.

### Enhanced Features
[![Download](https://img.shields.io/maven-central/v/com.android.tine/enhances.svg)](https://repo1.maven.org/maven2/com/android/tine/enhances/)

With [Dobby](https://github.com/jmpews/Dobby), you can use some enhanced features:
```groovy
implementation 'com.android.tine:enhances:<version>'
```

- Delay hook (aka pending hook) support, hooking static methods without initializing its declaring class immediately:
```java
TineEnhances.enableDelayHook();
```

### ProGuard
If you use Xposed features and Xposed APIs need to be called outside your module (e.g. you call `TineModule.loadModule()` to load external modules):
```
# Keep Xposed APIs
-keep class com.android.bridge.** { *; }
-keep class android.** { *; }
```

## Known issues
- May not be compatible with some devices/systems.

- Due to [#11](https://github.com/canyie/Tine/issues/11), we recommend hooking methods with less concurrency as much as possible, for example:
```java
public static void method() {
    synchronized (sLock) {
        methodLocked();
    }
}

private static void methodLocked() {
    // ...
}
```
In the example, we recommend you to hook `methodLocked` instead of `method`.

- Tine will disable hidden api policy on initialization by default. Due to an ART bug, if a thread changes hidden api policy while another thread is calling a API that lists members of a class, a out-of-bounds write may occur and causes crashes. We have no way to fix system bugs, so the only way is, initialize our library before other threads is started to avoid the race condition. For more info, see tiann/FreeReflection#60.

- For more, see [issues](https://github.com/canyie/Tine/issues).

## Discussion
[QQ Group：949888394](https://shang.qq.com/wpa/qunwpa?idkey=25549719b948d2aaeb9e579955e39d71768111844b370fcb824d43b9b20e1c04)
[Telegram Group: @DreamlandFramework](https://t.me/DreamlandFramework)

## 关注公众号
<div align="center">
  <img src="https://blog-img-1393828675.cos.ap-shanghai.myqcloud.com/rreversewechat/wechatsearch.png" alt="关注公众号" width="320" />
  <br/>
  <sub>扫码或微信搜索关注公众号，获取更多 Android 逆向 / Hook 相关内容与更新</sub>
</div>

## Credits
- [SandHook](https://github.com/ganyao114/SandHook)
- [Epic](https://github.com/tiann/epic)
- [AndroidELF](https://github.com/ganyao114/AndroidELF)
- [FastHook](https://github.com/turing-technician/FastHook)
- [YAHFA](https://github.com/PAGalaxyLab/YAHFA)
- [Dobby](https://github.com/jmpews/Dobby)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libcxx-prefab](https://github.com/RikkaW/libcxx-prefab)

## License
[Tine](https://github.com/canyie/Tine) Copyright (c) [canyie](http://github.com/canyie)

[AndroidELF](https://github.com/ganyao114/AndroidELF)  Copyright (c) [Swift Gan](https://github.com/ganyao114)

[Dobby](https://github.com/jmpews/Dobby)  Copyright (c) [jmpews](https://github.com/jmpews)

Licensed under the Anti 996 License, Version 1.0 (the "License");

you may not use this "Tine" project except in compliance with the License.

You may obtain a copy of the License at

https://github.com/996icu/996.ICU/blob/master/LICENSE


compile: gradlew :core:assembleRelease :xposed:assembleRelease