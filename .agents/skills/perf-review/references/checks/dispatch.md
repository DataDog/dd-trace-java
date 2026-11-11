# Polymorphic dispatch

Checks #5, J2. **J2 is parked: do not raise megamorphism findings in review.**

## Checks

- **#5 — Polymorphic dispatch on a hot path** — *a hot call site becomes polymorphic enough to
  defeat the runtime's inlining/devirtualization (real for JIT runtimes — JVM/.NET/V8;
  AOT/interpreted differ)* — **flag-as-measure** ("may defeat devirtualization; verify on the
  target runtime") — P3 — fix: keep hot call sites mono/bi-morphic; specialize.
- **J2 — Megamorphic dispatch** *(refines #5)*. **Status — PARKED for PR-review flagging
  (2026-07-08): do NOT raise megamorphism findings in review yet.** Kept as author-reference + the
  standing-audit target described below, not as an active review idiom. Rationale: it's too
  in-the-weeds to land with most devs, and the rubric must first bank *legible* wins (allocation,
  unbounded memory, regex — what anyone can see in a profiler) to earn trust before deploying the
  subtle JIT checks. Revisit once the rubric has a track record. (Mechanism below stands; it's the
  flagging that's held.) A hot call site seeing **≥3 receiver types with no dominant one** goes
  megamorphic. **Do not frame this as "the virtual call is slow" — a well-predicted indirect branch
  is a couple of cycles; the dispatch is a rounding error.** The cost is the **optimization fence
  the un-inlinable call erects on *both* sides**: caller-side, the args escape into an opaque
  callee → no scalar-replacement/EA on them, no constant-propagation *into* the call, caller-saved
  registers spilled across it, no hoist/reorder across the boundary; callee-side, it's never
  specialized to *this* caller, so the arg types/constants that would have collapsed its internal
  branches and devirtualized its *own* downstream calls stay invisible. Inlining is what lets the
  two bodies optimize as **one unit**; the megamorphic call severs that — on the hottest paths
  that is the whole bill. **C2 rescue conditions (the primary target):** ≤2 types stays
  **bimorphic** (inlinable); a **dominant receiver** (≥`TypeProfileMajorReceiverPercent`, default
  90%) still gets guarded mono-inline + uncommon-trap fallback, so a *skewed* site is usually fine
  — but watch **bimodal oscillation** (a recurring rare type → deopt thrash). The danger zone is
  the **flat ≥3 distribution** (`TypeProfileWidth`=2 → profile overflow → itable v-call, no
  inline). Fixes, cheapest first: keep the site to ≤2 types; **gate-when-empty** so the common case
  skips the fan-out (a default-empty listener array); **collapse N impls into one** `final` class
  with an internal state/size-class switch (e.g. retire a legacy map impl so the optimized one is
  the sole impl → every `TagMap` site monomorphic); or **call-site-split/unroll** a stable-order
  fan-out. **flag-as-measure** — opaque; whether it bites depends on which impls
  actually load + the runtime receiver mix. Confirm with `-XX:+PrintInlining` (`not inlined
  (megamorphic)`) / JITWatch, not a code read. **Diff-review blind spot → needs a standing
  audit.** The worst megamorphic sites *accumulate*: no single PR introduces them (each only nudges
  the type count by one), so a per-PR check catches only a PR that *widens* a site — the
  pre-existing hazards are invisible to it. The heaviest is `Context.get`/`with` dispatching across
  `Empty`/`Singleton`/`Indexed`(+wrapper) impls — the hottest path in the system.
  These want a **periodic PrintInlining census of the known hot sites**, run independent of any
  PR, not diff review.
