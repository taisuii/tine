# Tine
## 建议大家还是用Lsplant或者把本项目当作魔改版的pine，又继续研究了半天，只能缓解GC问题，不能根治😭
[![LICENSE](https://img.shields.io/badge/license-Anti%20996-blue.svg)](https://github.com/996icu/996.ICU/blob/master/LICENSE)
[![Android](https://img.shields.io/badge/Android-4.4%20~%2015-3DDC84.svg)](#supported-versions)

[中文文档](README_cn.md)

**Tine** is a hardened, re-branded fork of [Pine](https://github.com/canyie/pine) — a runtime, in-process Java method hook framework for the Android ART runtime. It can intercept almost any Java method call inside the current process, without root and without modifying the APK.

This fork is **not a drop-in re-upload**. It changes two things that matter in practice:

1. **Fingerprint reduction** — the framework was renamed from Pine to Tine so the most obvious, easily-scanned static signatures (Java package, public class names, the shipped `.so`/`.aar` names) no longer scream "Pine".
2. **A real crash fix** — it closes a long-standing `SIGSEGV` that happened when a moving GC relocated a hooked method's backup while you were calling the original implementation.

> Everything else (the ART internals, trampolines, Xposed bridge) is inherited from upstream Pine — full credit to [canyie](https://github.com/canyie). See [Credits](#credits).

---

## 📢 关注公众号 / Follow us

<div align="center">
  <img src="https://blog-img-1393828675.cos.ap-shanghai.myqcloud.com/rreversewechat/wechatsearch.png" alt="关注公众号" width="340" />
  <br/>
  <sub>微信扫码或搜索关注公众号，获取更多 Android 逆向 / Hook / 反检测 相关内容与本项目更新</sub>
</div>

---

## What this fork changes (vs. upstream Pine)

| Area | Upstream Pine | Tine (this fork) |
|------|---------------|------------------|
| Java package | `top.canyie.pine` | `com.android.tine` |
| Native library | `libpine.so` | `libtine.so` |
| Public entry classes | `Pine`, `PineConfig`, … | `Tine`, `TineConfig`, … |
| Maven coordinates | `top.canyie.pine:*` | `com.android.tine:*` |
| Moving-GC backup crash | present (`// FIXME: GC happens here ... will crash backup calling`) | **fixed** — see below |

### 🛡️ Fix: moving-GC backup `SIGSEGV`

Calling the original implementation of a hooked method (`invokeOriginalMethod` / `callBackupMethod`) could crash with a native `SIGSEGV` if an Android moving collector (CC on 8–12, CMC on 13+) relocated the backup method's `declaring_class` at the wrong moment. The backup `ArtMethod` is `malloc`-ed and therefore invisible to the GC, so its `declaring_class` was left dangling after a heap compaction.

Tine closes the window deterministically by disabling **only the moving GC** for the short duration of a backup call (the same primitive `GetPrimitiveArrayCritical` relies on), and degrades to the old behavior when the required ART symbols can't be resolved — so there is **no regression** on unsupported ROMs.

📄 Full write-up: [docs/moving-gc-backup-fix.md](docs/moving-gc-backup-fix.md)

> ⚠️ Honest scope note: fingerprint reduction currently covers the **Java/API layer and the shipped artifacts** (package, class names, `libtine.so`, `*.aar`). Internal C++ namespaces/symbols still contain some `pine` strings. If you need a deeper scrub, that is a known follow-up.

---

## <a name="supported-versions"></a>Supported versions

- Android **4.4 (ART only) ~ 15**, on `thumb-2` / `arm64`.
- Caveats: on Android 6.0 + arm32/thumb-2, parsed arguments may be wrong; on Android 9.0+, Tine disables the hidden-API restriction policy on init.

---

## 📦 Getting it

This fork is **not published to Maven Central** (the `com.android.tine:*` coordinates do not exist there). Use one of the two options below.

### Option A — Prebuilt AAR from Releases (recommended)

1. Download `core-release.aar` (plus `xposed-release.aar` / `enhances-release.aar` if you need them) from the [Releases](https://github.com/taisuii/tine/releases) page.
2. Drop them into your module's `libs/` directory.
3. Reference them in `build.gradle`:

```groovy
android {
    // AARs already contain the native libs (libtine.so, etc.)
}

dependencies {
    implementation files('libs/core-release.aar')
    // optional:
    // implementation files('libs/xposed-release.aar')
    // implementation files('libs/enhances-release.aar')
}
```

### Option B — Build from source

Requirements: Android SDK (platform 34), **NDK `25.2.9519653`**, **CMake `3.22.1`**, JDK 17.

```bash
# 1. Clone WITH submodules (xz-embedded + dobby are required to build the native code)
git clone --recursive https://github.com/taisuii/tine.git
cd tine
# already cloned without --recursive? then:
git submodule update --init --recursive

# 2. Point the build at your SDK (this repo intentionally does not commit local.properties)
echo "sdk.dir=/absolute/path/to/Android/Sdk" > local.properties

# 3. Build the release AARs
./gradlew :core:assembleRelease :xposed:assembleRelease :enhances:assembleRelease
```

Outputs land in `*/build/outputs/aar/`.

---

## 🚀 Usage

```java
// Configure once, as early as possible (ideally before other threads start)
TineConfig.debug = true;                    // verbose logs
TineConfig.debuggable = BuildConfig.DEBUG;  // keep this in sync with your app's debuggable flag
```

### Hook a method (before / after)

```java
Tine.hook(Activity.class.getDeclaredMethod("onCreate", Bundle.class), new MethodHook() {
    @Override public void beforeCall(Tine.CallFrame callFrame) {
        Log.i(TAG, "before " + callFrame.thisObject + ".onCreate()");
    }
    @Override public void afterCall(Tine.CallFrame callFrame) {
        Log.i(TAG, "after  " + callFrame.thisObject + ".onCreate()");
    }
});
```

`Tine.CallFrame` is the equivalent of Xposed's `MethodHookParam` — read/replace `args`, read `thisObject`, set the return value, or call the original via `callFrame.invokeOriginalMethod()`.

### Replace a method outright

```java
// e.g. let any thread touch the UI (don't ship this — for testing only)
Method checkThread = Class.forName("android.view.ViewRootImpl").getDeclaredMethod("checkThread");
Tine.hook(checkThread, MethodReplacement.DO_NOTHING);
```

### Xposed-style API (`com.android.tine:xposed` / `xposed-release.aar`)

Tine can run Xposed-style hooks and load Xposed modules. **Only Java method hooking is supported** — modules relying on Resource hooks, `TsSharedPreferences`, etc. won't work.

```java
TsHelpers.findAndHookMethod(TextView.class, "setText",
        CharSequence.class, TextView.BufferType.class, boolean.class, int.class,
        new TsMethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = "hooked";
            }
        });

// load an Xposed module
TineModule.loadModule(new File(modulePath));
TineModule.onPackageLoad(packageName, processName, appInfo, isFirstApp, classLoader);
```

If module APIs are called from outside your module, keep them with ProGuard:

```
-keep class com.android.bridge.** { *; }
-keep class android.** { *; }
```

### Enhanced features (`com.android.tine:enhances` / `enhances-release.aar`)

Backed by [Dobby](https://github.com/jmpews/Dobby). Enables, for example, **delay (pending) hook** — hook a static method without forcing its declaring class to initialize immediately:

```java
TineEnhances.enableDelayHook();
```

> Hooks only take effect **in the current process**. To affect another process, inject your code into it first — that is out of scope for this library.

---

## ⚠️ Known issues

- May be incompatible with some devices/ROMs.
- Prefer hooking low-concurrency methods. If a hot, highly-concurrent method must be hooked, hook a less-contended inner method instead:

```java
public static void method() {
    synchronized (sLock) { methodLocked(); }   // hook methodLocked(), not method()
}
private static void methodLocked() { /* ... */ }
```

- Tine disables the hidden-API policy on init. Due to an ART bug, changing that policy from one thread while another lists a class's members can trigger an out-of-bounds write and crash. Initialize Tine **before** other threads start to avoid the race (see [tiann/FreeReflection#60](https://github.com/tiann/FreeReflection/issues/60)).
- Found a bug? Open an issue at [taisuii/tine/issues](https://github.com/taisuii/tine/issues).

---

## Credits

This project stands entirely on upstream **[Pine](https://github.com/canyie/pine)** by [canyie](https://github.com/canyie); the implementation principle is described in [this article](https://canyie.github.io/2020/04/27/dynamic-hooking-framework-on-art/).

- [Pine](https://github.com/canyie/pine) — the upstream framework this fork is built on
- [SandHook](https://github.com/ganyao114/SandHook) · [Epic](https://github.com/tiann/epic) · [YAHFA](https://github.com/PAGalaxyLab/YAHFA) · [FastHook](https://github.com/turing-technician/FastHook)
- [AndroidELF](https://github.com/ganyao114/AndroidELF) — ELF symbol lookup
- [Dobby](https://github.com/jmpews/Dobby) · [LSPosed](https://github.com/LSPosed/LSPosed) · [libcxx-prefab](https://github.com/RikkaW/libcxx-prefab)

## License

Licensed under the **Anti 996 License, Version 1.0**. You may obtain a copy at
<https://github.com/996icu/996.ICU/blob/master/LICENSE>.

Pine Copyright (c) [canyie](https://github.com/canyie) · AndroidELF Copyright (c) [Swift Gan](https://github.com/ganyao114) · Dobby Copyright (c) [jmpews](https://github.com/jmpews).
