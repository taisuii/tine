# Tine

[![LICENSE](https://img.shields.io/badge/license-Anti%20996-blue.svg)](https://github.com/996icu/996.ICU/blob/master/LICENSE_CN)
[![Android](https://img.shields.io/badge/Android-4.4%20~%2015-3DDC84.svg)](#支持的版本)

[English](README.md)

**Tine** 是基于 [Pine](https://github.com/canyie/pine) 二次开发的 ART 运行时 Java 方法 Hook 框架。它运行在目标进程内部，可以拦截当前进程几乎所有的 Java 方法调用，无需 root、无需改包。

本 fork **不是简单的换皮重传**，它实质性地做了两件事：

1. **降低指纹** —— 把框架从 Pine 改名为 Tine，使最容易被静态扫描命中的特征（Java 包名、公开类名、随包发布的 `.so`/`.aar` 名称）不再直接暴露为 "Pine"。
2. **修复一个真实崩溃** —— 解决了「调用被 Hook 方法原实现时，移动 GC 搬动 backup 方法导致 `SIGSEGV`」的老问题。

> 其余部分（ART 内部处理、trampoline、Xposed 桥接）均继承自上游 Pine，全部功劳归 [canyie](https://github.com/canyie)，详见[致谢](#致谢)。

---

## 📢 关注公众号

<div align="center">
  <img src="https://blog-img-1393828675.cos.ap-shanghai.myqcloud.com/rreversewechat/wechatsearch.png" alt="关注公众号" width="340" />
  <br/>
  <sub>微信扫码或搜索关注公众号，获取更多 Android 逆向 / Hook / 反检测 相关内容与本项目更新</sub>
</div>

---

## 本 fork 相比上游 Pine 改了什么

| 维度 | 上游 Pine | Tine（本 fork） |
|------|-----------|-----------------|
| Java 包名 | `top.canyie.pine` | `com.android.tine` |
| Native 库 | `libpine.so` | `libtine.so` |
| 公开入口类 | `Pine`、`PineConfig` … | `Tine`、`TineConfig` … |
| Maven 坐标 | `top.canyie.pine:*` | `com.android.tine:*` |
| 移动 GC backup 崩溃 | 存在（源码里 `// FIXME: GC happens here ... will crash backup calling`） | **已修复**（见下） |

### 🛡️ 修复：移动 GC 导致 backup 调用 `SIGSEGV`

调用被 Hook 方法的原实现（`invokeOriginalMethod` / `callBackupMethod`）时，如果 Android 的**移动型 GC**（8–12 的 CC、13+ 的 CMC）在错误时机搬动了 backup 方法的 `declaring_class`，会触发 native `SIGSEGV`。原因是 backup 这个 `ArtMethod` 是 `malloc` 出来的、GC 不可见，堆压缩后它的 `declaring_class` 变成野指针。

Tine 的修复：在一次 backup 调用期间，只禁用**移动型 GC**（即 `GetPrimitiveArrayCritical` 内部所用的原语），非移动 GC 照常运行；当相关 ART 符号无法解析时自动退回原有行为，**零回归**。

📄 完整说明：[docs/moving-gc-backup-fix.md](docs/moving-gc-backup-fix.md)

> ⚠️ 如实说明：目前的指纹消减覆盖的是 **Java/API 层与发布产物**（包名、类名、`libtine.so`、`*.aar`）。C++ 内部命名空间/符号仍残留部分 `pine` 字样，更彻底的清理是已知的后续项。

---

## <a name="支持的版本"></a>支持的版本

- Android **4.4（仅 ART）~ 15**，`thumb-2` / `arm64` 指令集。
- 注意：Android 6.0 + arm32/thumb-2 上参数解析可能错误；Android 9.0+ 上 Tine 初始化时会关闭系统隐藏 API 限制策略。

---

## 📦 如何接入

本 fork **未发布到 Maven Central**（`com.android.tine:*` 这套坐标在中央仓库并不存在），请用下面两种方式之一。

### 方式 A —— 使用 Releases 里的预编译 AAR（推荐）

1. 从 [Releases](https://github.com/taisuii/tine/releases) 页面下载 `core-release.aar`（需要的话再下载 `xposed-release.aar` / `enhances-release.aar`）。
2. 放进你模块的 `libs/` 目录。
3. 在 `build.gradle` 中引用：

```groovy
dependencies {
    implementation files('libs/core-release.aar')   // AAR 已内置 libtine.so 等 native 库
    // 可选：
    // implementation files('libs/xposed-release.aar')
    // implementation files('libs/enhances-release.aar')
}
```

### 方式 B —— 从源码编译

环境要求：Android SDK（platform 34）、**NDK `25.2.9519653`**、**CMake `3.22.1`**、JDK 17。

```bash
# 1. 带子模块克隆（xz-embedded + dobby 是编译 native 必需的）
git clone --recursive https://github.com/taisuii/tine.git
cd tine
# 如果已经普通克隆过：
git submodule update --init --recursive

# 2. 指定 SDK 路径（本仓库刻意不提交 local.properties）
echo "sdk.dir=/你的/Android/Sdk/绝对路径" > local.properties

# 3. 编译 release AAR
./gradlew :core:assembleRelease :xposed:assembleRelease :enhances:assembleRelease
```

产物在 `*/build/outputs/aar/` 下。

---

## 🚀 使用

```java
// 尽早配置一次（最好在其他线程启动之前）
TineConfig.debug = true;                    // 输出更详细的日志
TineConfig.debuggable = BuildConfig.DEBUG;  // 与应用的 debuggable 保持一致，否则可能出问题
```

### Hook 一个方法（前/后）

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

`Tine.CallFrame` 相当于 Xposed 的 `MethodHookParam`：可读写 `args`、读取 `thisObject`、设置返回值，或用 `callFrame.invokeOriginalMethod()` 调用原方法。

### 直接替换一个方法

```java
// 例如允许任意线程更新 UI（仅用于测试，切勿上线）
Method checkThread = Class.forName("android.view.ViewRootImpl").getDeclaredMethod("checkThread");
Tine.hook(checkThread, MethodReplacement.DO_NOTHING);
```

### Xposed 风格 API（`com.android.tine:xposed` / `xposed-release.aar`）

Tine 支持以 Xposed 风格 Hook 方法并加载 Xposed 模块。**仅支持 Java 方法 Hook** —— 依赖资源 Hook、`TsSharedPreferences` 等特性的模块无法工作。

```java
TsHelpers.findAndHookMethod(TextView.class, "setText",
        CharSequence.class, TextView.BufferType.class, boolean.class, int.class,
        new TsMethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = "hooked";
            }
        });

// 加载 Xposed 模块
TineModule.loadModule(new File(modulePath));
TineModule.onPackageLoad(packageName, processName, appInfo, isFirstApp, classLoader);
```

若模块 API 会被你模块外部调用，请用 ProGuard 保留：

```
-keep class com.android.bridge.** { *; }
-keep class android.** { *; }
```

### 增强功能（`com.android.tine:enhances` / `enhances-release.aar`）

借助 [Dobby](https://github.com/jmpews/Dobby)。例如支持 **延迟 Hook（pending hook）** —— Hook 静态方法时无需立刻初始化其所在类：

```java
TineEnhances.enableDelayHook();
```

> 所有 Hook 只在**当前进程**内生效。想影响其他进程，请先用你自己的手段把代码注入进去，这与本库无关。

---

## ⚠️ 已知问题

- 可能不兼容部分设备/ROM。
- 尽量 Hook 并发较低的方法。如果必须 Hook 热点高并发方法，建议改为 Hook 其中竞争较少的内部方法：

```java
public static void method() {
    synchronized (sLock) { methodLocked(); }   // Hook methodLocked()，而不是 method()
}
private static void methodLocked() { /* ... */ }
```

- Tine 初始化时会关闭隐藏 API 限制。由于 ART 的一个 bug：一个线程修改该策略、另一个线程正在列举某个类的成员时，可能发生越界写并崩溃。请在其他线程启动**之前**初始化 Tine 以避免竞态（参见 [tiann/FreeReflection#60](https://github.com/tiann/FreeReflection/issues/60)）。
- 发现问题请到 [taisuii/tine/issues](https://github.com/taisuii/tine/issues) 反馈。

---

## 致谢

本项目完全建立在 [canyie](https://github.com/canyie) 的上游 **[Pine](https://github.com/canyie/pine)** 之上，实现原理见[这篇文章](https://canyie.github.io/2020/04/27/dynamic-hooking-framework-on-art/)。

- [Pine](https://github.com/canyie/pine) —— 本 fork 所基于的上游框架
- [SandHook](https://github.com/ganyao114/SandHook) · [Epic](https://github.com/tiann/epic) · [YAHFA](https://github.com/PAGalaxyLab/YAHFA) · [FastHook](https://github.com/turing-technician/FastHook)
- [AndroidELF](https://github.com/ganyao114/AndroidELF) —— ELF 符号搜索
- [Dobby](https://github.com/jmpews/Dobby) · [LSPosed](https://github.com/LSPosed/LSPosed) · [libcxx-prefab](https://github.com/RikkaW/libcxx-prefab)

## 许可证

基于 **反 996 许可证 1.0 版** 授权，副本见
<https://github.com/996icu/996.ICU/blob/master/LICENSE_CN>。

Pine Copyright (c) [canyie](https://github.com/canyie) · AndroidELF Copyright (c) [Swift Gan](https://github.com/ganyao114) · Dobby Copyright (c) [jmpews](https://github.com/jmpews)。
