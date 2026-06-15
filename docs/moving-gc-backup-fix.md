# Fix: SIGSEGV when a moving GC relocates a backup method's declaring class

## Summary

Calling the original implementation of a hooked method (`invokeOriginalMethod` /
`callFrame.invokeOriginalMethod` / `HookRecord.callBackup`) could crash with a native
`SIGSEGV` when an Android moving garbage collector ran at the wrong moment. This is the
crash the previous code acknowledged with the in-source comment:

```java
// FIXME: GC happens here (you can add Runtime.getRuntime().gc() to test) will crash backup calling
```

This fix closes that window deterministically by disabling the **moving** GC for the short
duration of a backup call. When the required ART symbols are unavailable, it degrades to the
previous behavior, so there is no regression on unsupported ROMs.

## Symptom

- Native `SIGSEGV` (signal 11), most often during process startup / under heavy allocation,
  when many hooks are installed and the GC is firing frequently.
- Probabilistic: the same app may crash on the first cold start, crash later on the second,
  and become stable after a few launches (GC frequency drops sharply once AOT/JIT warms up).
- Often preceded by the log line from `Tine_syncMethodInfo`:
  `GC moved declaring class of method %p, also update in backup %p`.

## Root cause

The backup `ArtMethod` is allocated with `malloc` (`ArtMethod::New()` in
`core/src/main/cpp/art/art_method.h`), so it is **invisible to the GC**. Its
`declaring_class` field is a raw (compressed) `GcRoot<Class>`.

When a moving collector (CC on Android 8–12, the userfaultfd-based CMC on Android 13+)
relocates the declaring `Class`, the runtime updates every *real* `ArtMethod` it can reach as
a root — through each class's method arrays and through live stack frames — but it never
visits the detached backup. The backup's `declaring_class` is left pointing at the old,
now-freed location: a dangling pointer.

`Tine.callBackupMethod` papered over this with a lazy resync immediately before the call:

```java
syncMethodInfo(origin, backup, ...);   // copy current declaring_class into the backup
Object result = backup.invoke(...);     // <-- window: a moving GC here re-stales it
```

But `Method.invoke` runs a long path full of GC safepoints (boxing, allocation, class-init
checks). If a moving GC fires in that window it relocates the `Class`, the backup goes stale
again, and the next read of `declaring_class` (during invoke setup, or when the GC later walks
a stack frame whose method is the backup) dereferences garbage → `SIGSEGV`.

Key observation: once the backup is *executing on a stack frame*, the moving GC's stack-root
scan updates its `declaring_class` in place, so it is safe. The only dangerous interval is
between "we wrote `declaring_class`" and "the backup frame becomes visible to the stack
scanner". Moving GC only happens at safepoints, so if that interval contains no safepoint at
which the class can move, the race cannot occur.

## The fix

Disable the moving GC for the duration of the backup call, using the same primitive that
`GetPrimitiveArrayCritical` relies on to safely hold a raw heap pointer:

```java
// Tine.callBackupMethod
long gcGuard = beginCallBackup();   // Heap::IncrementDisableMovingGC + wait for in-progress moving GC
try {
    syncMethodInfo(origin, backup, hookRecord.skipUpdateDeclaringClass);
    return backup.invoke(thisObject, args);
} finally {
    endCallBackup(gcGuard);         // Heap::DecrementDisableMovingGC
}
```

- `beginCallBackup()` calls `art::gc::Heap::IncrementDisableMovingGC(self)`, which also
  **waits for any in-progress moving collection to finish**. After it returns, the declaring
  class is at its final address; `syncMethodInfo` copies that address into the backup, and the
  class cannot move again until `endCallBackup()`.
- Order matters: **disable moving GC first, then sync, then invoke.**
- Only the *moving* GC is disabled. Non-moving collection still runs, so allocations inside the
  invoked method cannot deadlock or spuriously OOM. (This is exactly why a full
  `ScopedSuspendAll` / `ScopedGCCriticalSection` around the call would be wrong — it could block
  allocation-triggered GC inside arbitrary user code.)
- The counter-based primitive is re-entrant, so nested/recursive backup calls are safe.

### Implementation

| File | Change |
|------|--------|
| `core/src/main/cpp/android.h` | `heap_` + `Increment/DecrementDisableMovingGC` function pointers; `CanDisableMovingGc()` helpers; `ScopedDisableMovingGc` RAII (mirrors `ScopedSuspendVM`). |
| `core/src/main/cpp/android.cpp` | `InitDisableMovingGc()`: resolve `Heap::{Increment,Decrement}DisableMovingGC` symbols and obtain `art::gc::Heap*` via `Runtime::GetHeap()`. |
| `core/src/main/cpp/tine.cpp` | `beginCallBackup()` / `endCallBackup()` JNI bridge, registered in `gMethods[]`. |
| `core/src/main/java/com/android/tine/Tine.java` | `callBackupMethod` wraps sync + invoke in `begin/endCallBackup`; native declarations. |
| `app/src/androidTest/.../AutomatedTest.java` | New step: hammer hooked-method calls while a second thread forces GC. |

## Behavior when ART symbols are unavailable (no regression)

`Heap*` is obtained via the `Runtime::GetHeap()` symbol. On ROMs where that symbol (or the
`IncrementDisableMovingGC` / `DecrementDisableMovingGC` symbols) cannot be resolved,
`CanDisableMovingGc()` is `false`, `ScopedDisableMovingGc` becomes a no-op, and
`callBackupMethod` falls back to exactly the previous lazy-sync behavior. The guard never makes
things worse than before.

> If a target ROM does not export `Runtime::GetHeap()` (it is often inlined away), the guard is
> inert. Resolving `Heap*` by offset within `Runtime` was intentionally **not** implemented:
> dereferencing a mis-located pointer inside `IncrementDisableMovingGC` would be worse than the
> bug being fixed. Offset-based acquisition can be added as a follow-up, calibrated per ART
> version, if needed.

## How to verify

1. Reproduce (before the fix): temporarily insert `Runtime.getRuntime().gc()` between
   `syncMethodInfo` and `backup.invoke`, hook a method, and call its original repeatedly — it
   crashes reliably.
2. Run the new `AutomatedTest` step: hooked-method calls under concurrent GC pressure must not
   crash.
3. On device, confirm activation via logcat:
   - `Moving-GC guard for backup calls enabled (heap=0x...)` → the guard is active.
   - `Could not resolve art::gc::Heap*` → symbols missing, safe fallback engaged.
   - Strong signal that the race is closed: the `GC moved declaring class ...` log drops to
     zero, because the class no longer moves during a backup call.

## Affected versions

Android L (5.0) through V (15). Kitkat and below have no moving GC (all objects are immovable),
so they are unaffected and the guard short-circuits.

---

## 中文小结

调用被 hook 方法的原实现（`invokeOriginalMethod` 等）时，若 ART 的**移动型 GC** 在错误时机
搬动了 backup 方法的 `declaring_class`，会触发 native `SIGSEGV`。这正是原代码里那条
`FIXME: GC happens here ... will crash backup calling` 所指的崩溃。

根因：backup 是 `malloc` 出来、GC 不可见的 `ArtMethod`，其 `declaring_class` 是裸 GcRoot；
moving GC（Android 13+ 为 CMC）搬动 Class 后只更新真实方法，不更新孤儿 backup → 野指针。
致命窗口在 `callBackupMethod` 的 `syncMethodInfo → backup.invoke` 之间（`Method.invoke` 路径
满是安全点）。

修复：在 backup 调用期间用 `Heap::IncrementDisableMovingGC`（`GetPrimitiveArrayCritical` 内部
所用原语）只禁用**移动型** GC——非移动 GC 照常运行，不会分配死锁。顺序铁律：先禁 GC、再 sync、
后 invoke。若相关 ART 符号无法解析，则自动退回原惰性同步行为，**零回归**。
