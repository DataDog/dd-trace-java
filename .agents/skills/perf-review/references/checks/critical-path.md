# Critical-path work and native crossings

Checks #4, #6, J3.

## Checks

- **#4 — Expensive work on the critical path that could be deferred** — *heavy compute / parse /
  normalize / serialize / I/O / lock on the synchronous request or span-finish path, that could be
  moved* — flag-as-*consider* (deferability is contextual) — SEV-1/2 — fix: defer to
  background/writer thread, lazy-compute, batch.
- **#6 — FFI / native-boundary crossing on a hot path** *(central to the shared-core effort)* — *a
  native crossing per-span/per-item (not batched), or transporting strings/objects rather than
  primitives/IDs* — flag-with-confidence (boundary cost is mechanism-determined; runtime-specific
  pinning → J3) — SEV-1/2 (SEV-1 if it blocks/pins under concurrency) — fix: batch (one per flush,
  not per item); transport interned IDs not strings; keep crossings off the hot/concurrency path.
- **J3 — JNI / native crossing: overhead + virtual-thread pinning** *(refines #6)*. JNI call ≈
  100ns–1µs (state transition, arg pin/copy, no inlining across); string args via
  `GetStringUTFChars` = UTF-16→UTF-8 copy. **A JNI call from a virtual thread pins the carrier** →
  no other vthreads on that carrier run while pinned → concurrency collapse for vthread-reliant
  apps. Fix: batch at flush on the **writer (platform) thread**, keep the app-vthread path
  pure-Java, transport interned IDs not strings; `@CriticalNative` only for short primitive ops (no
  `JNIEnv`, no object args, no GC-safe state — severe constraints). Flag the crossing + pinning
  risk (mechanism); overhead magnitude → measure.
