# Instrumentation (ByteBuddy Advice) idioms

dd-trace-java-specific fixes. Load when the diff touches `dd-java-agent/` or any code using
`@Advice`.

The core rule is a **predicate-with-default, not a banned-API list**: don't flag "you called
`String.format`"; flag *"an eager, unconditional expensive call on an instrumentation-reachable
path."* Two failure shapes, different fixes: (1) the result is usually **discarded** →
**gate/defer** (parameterized logging, `isEnabled()` guard) — the cost is avoidable; (2) the
result is always **needed but costly** → **cheapen/cache** — gating does nothing. The
discriminator (eager? unconditional? guarded? result-needed?) is interprocedural — that's the
review's job (`../posture.md`). These idioms are the actionable fixes when a universal/Java finding
lands on an `@Advice` path:

- **`Config.get()` / `InstrumenterConfig.get()` on a hot path — flag-with-confidence.** Walks a
  config-resolution chain; not a free read. Fix: hoist to a `static final` field, or compute once
  in the constructor. Common trap: the call is buried in a helper invisible at the advice site —
  grep transitively. (The single most recurring finding in calibration — five independent
  occurrences.)
- **`@Advice.AllArguments()` — deterministic lint.** Materializes a new `Object[]` boxing all
  arguments on every advised call; always escapes, EA cannot elide it. Fix:
  `@Advice.Argument(value=N)` for the one argument and type needed.
- **`@Advice.SkipOn(OnDefaultValue.class)` + cached boolean — the preferred feature-flag
  pattern.** Compute a `static final boolean` once, return it from `@Advice.OnMethodEnter`,
  suppress exit advice when disabled. Residual cost: one JIT-hoistable field read per call.
  Recommend this whenever a `Config.get()` shows up in advice.
- **`@Advice.Local` — prefer over `ThreadLocal`.** Carries per-invocation state from
  `OnMethodEnter` to `OnMethodExit` with no map lookup.
- **Reflective `@Advice.Origin Method` / `Constructor` on a hot advice.** The reflective origin
  object is the costly form (per-access reflective lookup). A **String** origin
  (`@Advice.Origin("#m") String`) is injected as a compile-time constant — no per-call allocation,
  **don't flag it** (it's the intended cheap form, widely used). Flag only reflective
  `Method`/`Constructor`/`Executable` origins on a hot advice path; fix: pass the constant String
  pattern (`#m`, `#t`) instead of the reflective object.
- **Java Stream API on an advice/hot path — flag-with-confidence.** `stream()`/`IntStream`
  allocate `Spliterator` + pipeline objects per call; JIT elision is fragile. Fix: a plain `for`
  loop (`cstyleFor`/`enhancedFor`/`forEach`/`iterator` are all on par, ≈0 alloc). See
  `repeat-work.md` for the benchmark numbers. Stay silent in a cache-miss body, a length-guarded
  error path, or cold/startup code.
- **`switch(String)` — three-tier fix ranking.** (1) resolve to a constant `long` id (folds on any
  JIT); (2) open-addressed table (never folds but always inlinable, low-variance); (3) plain
  `switch` (fine for small, inlinable dispatch). Flag *large* switches (inline-budget exhaustion is
  mechanism-certain); flag *small* switches on hot constant-arg paths only as a
  version-conditional soft-alert.
