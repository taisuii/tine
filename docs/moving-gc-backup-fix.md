# 修复：移动 GC 搬动 backup 方法的 declaring_class 引发 SIGSEGV

## 概述

调用被 hook 方法的原实现（`invokeOriginalMethod` / `callFrame.invokeOriginalMethod` /
`HookRecord.callBackup`，最终都走到 `Tine.callBackupMethod`）时，如果 Android 的**移动式垃圾回收器**
在错误的时机搬动了 backup 方法的 `declaring_class`，会触发 native `SIGSEGV`。这正是上游 Pine
源码里那行注释所指的崩溃：

```java
// FIXME: GC happens here (you can add Runtime.getRuntime().gc() to test) will crash backup calling
```

本补丁通过在一次 backup 调用期间**只禁用移动式 GC**，确定性地关闭这个竞态窗口。当所需的 ART
符号无法解析时，自动退回到原有行为，因此在不支持的 ROM 上**零回归**。

> 这个 bug 来自上游 [canyie/pine](https://github.com/canyie/pine)，长期存在但未修复；本补丁在其 fork
> [Tine](https://github.com/taisuii/tine) 中合入。

---

## 背景知识

为了让后面的分析能读懂，先交代几个概念。

### Pine/Tine 的 hook 与 backup 方法

Tine 走的是 ART 方法替换：把目标方法对应的 `ArtMethod` 的入口指向自己的 trampoline，同时**克隆一份
原方法**保存下来，称为 **backup**。当你在 hook 回调里调用原实现时，真正执行的就是这个 backup。
backup 的 `ArtMethod` 是这样分配的（`core/src/main/cpp/art/art_method.h`）：

```cpp
static ArtMethod* New() {
    return static_cast<ArtMethod*>(malloc(size));
}
```

注意：它是 **`malloc` 出来的裸内存，不在 ART 的托管堆上，GC 完全看不见它**。这是整件事的根。

### ArtMethod、declaring_class 与压缩引用

`ArtMethod` 里有一个 `declaring_class` 字段，指向方法所属的类对象 `mirror::Class`。在 ART 里，
堆对象之间的引用普遍用 **32 位压缩引用**（`GcRoot<Class>` / `HeapReference`）存储，所以
`declaring_class` 实际是一个 `uint32_t`——它本质上是一个指向托管堆的“裸引用”。Tine 的 native 代码
里也是按 `uint32_t` 读写它的：

```cpp
uint32_t declaring_class = origin->GetDeclaringClass();
backup->SetDeclaringClass(declaring_class);
```

裸引用的含义是：**它不被 GC 当作 root 来跟踪和修正**，除非这个 `ArtMethod` 本身能被 GC 通过某条
root 路径访问到。对真实方法来说这条路径是存在的（见下文），对游离的 backup 来说不存在。

### ART 的移动式 GC（按版本）

“移动式”指 GC 会在回收时**搬动存活对象**以压缩内存、消除碎片。被搬动的对象地址会变，所有指向它的
引用都必须被同步修正。各 Android 版本的情况：

- **Android 4.4 (KitKat) 及以下**：对象全部不可移动，不存在这个问题，本补丁直接短路。
- **Android 5.x (L)**：引入了带压缩的 GC。
- **Android 8\~12**：默认 **CC（Concurrent Copying）**，并发拷贝式，会搬。
- **Android 13+**：默认 **CMC（Concurrent Mark Compact）**，基于 userfaultfd 的并发标记-压缩，会搬。
  本文实测的 Android 16 用的就是它（开机日志可见 `Using CollectorTypeCMC GC.`）。

### GC 安全点（safepoint）

ART 的并发 GC 不能在任意指令边界搬动对象，它需要所有相关线程到达**安全点**才能安全地移动对象、
修正引用。方法调用、分配、循环回边、JNI 转换等位置都可能是安全点。**移动只发生在安全点**——这条
性质是本补丁能成立的关键：只要某段代码区间里不存在“类可以被移动”的安全点，搬动就不可能发生。

---

## 现象

- native `SIGSEGV`（signal 11），多发于**进程启动阶段 / 高分配压力**下，尤其是装了大量 hook 时。
- **概率性**：同一个 App 可能冷启动第一次崩、第二次不崩，跑顺几次后趋于稳定——因为 AOT/JIT 预热后
  GC 频率显著下降，撞上窗口的概率随之降低。
- 崩溃前 logcat 里常先出现来自 `Tine_syncMethodInfo` 的日志：
  `GC moved declaring class of method %p, also update in backup %p`。

在真机（Pixel 6 Pro / Android 16 / CMC）上构造确定性复现后，拿到的崩溃栈如下（已裁剪）：

```
F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x10 in tid (tine-gc-repro)
F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010 (read)
F DEBUG   :     x2 0000000000000010   x3 656800656e696c5f   ...
F DEBUG   : backtrace:
F DEBUG   :   #00 libart.so (art::mirror::Class::GetDescriptor(std::string*)+76)
F DEBUG   :   #01 libart.so (art::mirror::Class::PrettyDescriptor()+44)
F DEBUG   :   #02 libart.so (art::mirror::Class::PrettyClass()+124)
F DEBUG   :   #03 libart.so (art::ClassLinker::InitializeClass(...)+1928)
F DEBUG   :   #04 libart.so (art::ClassLinker::EnsureInitialized(...)+156)
F DEBUG   :   #05 libart.so (art::InvokeMethod<(art::PointerSize)8>(...)+1876)
F DEBUG   :   #06 libart.so (art::Method_invoke(...)+32)
```

逐帧解读：`#06 Method_invoke` → `#05 InvokeMethod` 是 `java.lang.reflect.Method.invoke` 的 native
实现；它在真正执行方法前要做**类初始化检查** `#04 EnsureInitialized` → `#03 InitializeClass`，
其中又去取类名 `#02 PrettyClass` → `#01 PrettyDescriptor` → `#00 GetDescriptor`，在这里读了一个
坏掉的 `Class*` 而崩溃。`fault addr 0x10`、`x2 = 0x10` 说明是在一个近乎空的 `Class*` 上读偏移
`0x10`；`x3 = 0x656800656e696c5f` 解出来是 `_line\0he` 之类的字符串字节——这个 `Class*` 指向的内存
已经**被搬走/释放后又被填入了别的数据**。典型的野指针读。

---

## 根因

backup 是 `malloc` 出来、GC 不可见的 `ArtMethod`，它的 `declaring_class` 是一个裸的（压缩）
`GcRoot<Class>`。

当一次移动式 GC（Android 8\~12 的 CC、13+ 的 CMC）搬动了某个 `declaring` 类时，运行时会把它能
作为 **root** 触达的所有**真实** `ArtMethod` 都更新一遍——通过每个类的 method array、通过活动
栈帧——但它**永远不会去访问那个被剥离出来的 backup**。于是 backup 的 `declaring_class` 仍指向旧
的、已被释放的位置：一个野指针。

`Tine.callBackupMethod` 此前用一次“调用前惰性补写”来掩盖这个问题：

```java
syncMethodInfo(origin, backup, ...);   // 把当前 declaring_class 抄进 backup
Object result = backup.invoke(...);     // <-- 窗口：这里发生一次移动 GC 就会让它重新失效
```

`syncMethodInfo` 做的事是：读真实方法当前的 `declaring_class`，若与 backup 中的不一致就抄过去
（上面那条 `GC moved declaring class ...` 日志就来自这里）：

```cpp
uint32_t declaring_class = origin->GetDeclaringClass();
if (declaring_class != backup->GetDeclaringClass()) {
    LOGI("GC moved declaring class of method %p, also update in backup %p", origin, backup);
    backup->SetDeclaringClass(declaring_class);
}
```

但 `Method.invoke` 的执行路径很长，充满 GC 安全点（参数装箱、数组分配、**类初始化检查**等）。
如果在“补写完”到“真正进入 backup”这段窗口里触发了一次移动 GC，它会搬动 `Class`，backup 随即
再次失效，紧接着 invoke 流程里的 `EnsureInitialized` 去读 `declaring_class`——就是上面那条崩溃栈。

上游代码里还有一句试图“续命”的写法：

```java
// Explicit use declaring_class object to ensure it has reference on stack
// and avoid being moved by gc. (invalid for now)
declaring.getClass();
```

意图是把 `declaring` 引用钉在栈上、让 GC 别搬它。但它是无副作用代码，会被优化、时机也不对，作者
自己都标了 `(invalid for now)`——拦不住。

---

## 关键观察

死磕“怎么在整个 invoke 路径上始终保住地址”是走不通的，因为路径太长、安全点太多。换个角度：

**一旦 backup 真正运行在栈帧上，它就安全了。** 因为移动 GC 的**栈 root 扫描**会顺着活动栈帧，把
帧上方法的 `declaring_class` **就地更新**掉。

所以真正危险的区间，只有“我们写完 `declaring_class`”到“backup 的栈帧对栈扫描可见”这一小段。
而移动只发生在安全点。于是结论很清晰：**只要这一小段区间里 `Class` 不能被移动，竞态就根本不会
发生**——不需要保住地址，只需要这段时间内不让 GC 搬东西。

---

## 修复方案

在一次 backup 调用期间禁用移动式 GC，用的是 `GetPrimitiveArrayCritical` 在持有裸堆指针期间所依赖的
同一把原语：

```java
// Tine.callBackupMethod
long gcGuard = beginCallBackup();   // Heap::IncrementDisableMovingGC + 等待在途的移动 GC 结束
try {
    syncMethodInfo(origin, backup, hookRecord.skipUpdateDeclaringClass);
    return backup.invoke(thisObject, args);
} finally {
    endCallBackup(gcGuard);         // Heap::DecrementDisableMovingGC
}
```

- `beginCallBackup()` 调用 `art::gc::Heap::IncrementDisableMovingGC(self)`，它还会**等待任何正在
  进行中的移动 GC 结束**。返回之后，`declaring` 类已经停在它的最终地址上；`syncMethodInfo` 把这个
  最终地址抄进 backup，并且在 `endCallBackup()` 之前这个类不可能再被移动。
- **顺序是铁律：先禁移动 GC，再 sync，最后 invoke。** 反过来都不对。
- 只禁用**移动式** GC，**非移动 GC 照常运行**，因此被调用方法内部的分配既不会死锁、也不会假性
  OOM。
- 这把原语是**计数器式、可重入**的，所以嵌套/递归的 backup 调用是安全的。

### 为什么不能用 STW / ScopedSuspendAll / ScopedGCCriticalSection

最容易想到的是把整个 VM 停掉（`ScopedSuspendAll`），或者用 `ScopedGCCriticalSection` 把整段调用
圈起来禁掉所有 GC。**这是错的。** backup 调用会执行任意用户代码，里面随时可能分配对象、触发
allocation GC；一旦你把所有 GC（包括非移动的回收）都卡住，被调用代码里的分配触发的 GC 无法推进，
就会**死锁或假性 OOM**。所以必须**精确地只禁“移动”这一种行为**，而保留回收能力。

---

## 实现细节

### Java 侧

`callBackupMethod` 用 `begin/endCallBackup` 把 sync + invoke 包起来（见上）。新增两个 native 声明：

```java
private static native long beginCallBackup();
private static native void endCallBackup(long cookie);
```

### native 侧：RAII guard + JNI 桥

`begin/end` 是一对 RAII guard 的生命周期，cookie（指针）透传回 Java：

```cpp
jlong Tine_beginCallBackup(JNIEnv* env, jclass) {
    void* self = art::Thread::Current(env);
    return reinterpret_cast<jlong>(new tine::ScopedDisableMovingGc(self));
}
void Tine_endCallBackup(JNIEnv*, jclass, jlong cookie) {
    delete reinterpret_cast<tine::ScopedDisableMovingGc*>(cookie);
}
```

`ScopedDisableMovingGc` 仿照项目已有的 `ScopedSuspendVM` 写，构造里 increment、析构里 decrement；
符号不可用时整体退化为 no-op：

```cpp
class ScopedDisableMovingGc {
public:
    explicit ScopedDisableMovingGc(void* self)
            : self_(self), active_(Android::CanDisableMovingGc()) {
        if (LIKELY(active_)) Android::IncrementDisableMovingGc(self_);
    }
    ~ScopedDisableMovingGc() {
        if (LIKELY(active_)) Android::DecrementDisableMovingGc(self_);
    }
    bool active() const { return active_; }
private:
    void* self_;
    bool active_;
};
```

### 符号解析：怎么拿到 `art::gc::Heap*`

`IncrementDisableMovingGC` 是 `art::gc::Heap` 的成员函数，调它得先有 `this`，即进程里的 `Heap`
实例。ART 不导出它，只能从符号里抠。函数符号按 mangled name 解析：

```cpp
increment_disable_moving_gc_ = ... handle->GetSymbolAddress(
        "_ZN3art2gc4Heap22IncrementDisableMovingGCEPNS_6ThreadE", false);
decrement_disable_moving_gc_ = ... handle->GetSymbolAddress(
        "_ZN3art2gc4Heap22DecrementDisableMovingGCEPNS_6ThreadE", false);
```

`Heap*` 本身走 `Runtime::instance_`（全局单例）→ `Runtime::GetHeap()`：

```cpp
void** instance_ptr = ... handle->GetSymbolAddress("_ZN3art7Runtime9instance_E", false);
void* runtime = instance_ptr ? *instance_ptr : nullptr;

auto get_heap = ... handle->GetSymbolAddress("_ZNK3art7Runtime7GetHeapEv", false); // const
if (!get_heap) get_heap = ... handle->GetSymbolAddress("_ZN3art7Runtime7GetHeapEv", false);
if (get_heap) heap_ = get_heap(runtime);
```

**一个刻意没做的决定**：这里没有去“猜” `heap_` 成员在 `Runtime` 结构里的偏移然后硬读。原因是
`Runtime` 结构非常大、`heap_` 离任何可校验的锚点都很远，且偏移随 ART 版本漂移——**一旦猜错，就是
把一个错误的指针喂进 `IncrementDisableMovingGC` 里解引用，后果比原 bug 更严重**。所以宁可走
`GetHeap()`；如果它在某些 ROM 上被 inline 掉、未导出，那就拿不到，拿不到就老实退化。按版本标定
offset 的方案可以作为后续单独做，默认不冒这个险。

### 涉及文件

| 文件 | 改动 |
|------|------|
| `core/src/main/cpp/android.h` | 新增 `heap_` + `Increment/DecrementDisableMovingGC` 函数指针；`CanDisableMovingGc()` 系列辅助；`ScopedDisableMovingGc` RAII（对标 `ScopedSuspendVM`）。 |
| `core/src/main/cpp/android.cpp` | `InitDisableMovingGc()`：解析 `Heap::{Increment,Decrement}DisableMovingGC` 符号，并通过 `Runtime::GetHeap()` 取得 `art::gc::Heap*`。 |
| `core/src/main/cpp/tine.cpp` | `beginCallBackup()` / `endCallBackup()` JNI 桥，注册进 `gMethods[]`。 |
| `core/src/main/java/com/android/tine/Tine.java` | `callBackupMethod` 用 `begin/endCallBackup` 包裹 sync + invoke；新增 native 声明。 |
| `app/src/androidTest/.../AutomatedTest.java` | 新增一步：在第二个线程持续强制 GC 的同时疯狂调用被 hook 方法。 |

---

## 符号不可用时的降级（零回归）

`Heap*` 经由 `Runtime::GetHeap()` 取得。在那些无法解析该符号（或 `IncrementDisableMovingGC` /
`DecrementDisableMovingGC` 符号）的 ROM 上，`CanDisableMovingGc()` 返回 `false`，
`ScopedDisableMovingGc` 变成 no-op，`callBackupMethod` 退回到与改动前完全一致的惰性补写行为。
这个 guard 永远不会让情况比原来更糟。

> 如果目标 ROM 没有导出 `Runtime::GetHeap()`（它经常被 inline 掉），guard 即处于惰性状态。
> 通过在 `Runtime` 内按偏移获取 `Heap*` 的做法被**刻意没有实现**：在 `IncrementDisableMovingGC`
> 里解引用一个错位的指针，比正在修的这个 bug 还糟。按 ART 版本标定的 offset 获取可以作为后续工作。

---

## 如何验证

### 1. 复现（修复前）

写一个最小复现：hook 一个静态方法，然后在堆分配压力下反复调用它的原实现；并在 `syncMethodInfo`
和 `backup.invoke` 之间插入 `Runtime.getRuntime().gc()`（即 FIXME 注释建议的测试手段）。在真机上
（Pixel 6 Pro / Android 16 / CMC）第一批迭代内即必崩：

```
I TineGcRepro: REPRO_START device=Pixel 6 Pro Android=16 API=36 abi=arm64-v8a
I TineGcRepro: hook installed; hammering backup calls under GC pressure...
I d.tine.examples: Explicit concurrent mark compact GC freed 2673KB AllocSpace bytes, 92% free, ...
F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x10 ...
F DEBUG   :   #00 art::mirror::Class::GetDescriptor(...)  ←  #03 InitializeClass  ←  #05 InvokeMethod
```

那行 `Explicit concurrent mark compact GC` 正是被强制触发的一次 **CMC 移动压缩**。

### 2. 修复后

复现工程一字不改，仅把 core 换成带本补丁的版本，同样 20 万次循环 + 同样 GC 压力，跑满正常退出：

```
I TineGcRepro: hook installed on victim(int); hammering backup calls under GC pressure...
I Tine    : handleCall for method public static int ...GcBugReproActivity.victim(int)
   ... 20 万次调用全部正常返回 ...
I TineGcRepro: REPRO_SURVIVED iterations=200000 sum=620002257824
```

### 3. 线上判断 guard 是否生效（logcat）

- `Moving-GC guard for backup calls enabled (heap=0x...)` → guard 已激活。
- `Could not resolve art::gc::Heap*` → 符号缺失，已安全退化。
- 强信号：`GC moved declaring class ...` 这条日志**降到 0**——因为在 backup 调用期间类不再移动，
  自然也就没有“搬动后补写”这回事了。

---

## 影响版本

Android L (5.0) 到 V (15)，以及实测的 Android 16（CMC）。KitKat 及以下没有移动式 GC（对象全部
不可移动），不受影响，guard 直接短路。

---

## 参考

- 上游框架：[canyie/pine](https://github.com/canyie/pine)
- 本补丁所在 fork：[taisuii/tine](https://github.com/taisuii/tine)
- 复现工程：`app/.../GcBugReproActivity.java`；压力测试：`app/src/androidTest/.../AutomatedTest.java`
