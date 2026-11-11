# Instrumentation (ByteBuddy Advice) idioms

Load when the diff touches `dd-java-agent/` or any code using `@Advice`. These are the fixes to
point to when a finding from another check lands on an `@Advice` path. Whether the call is eager,
unconditional and needed is decided the same way as everywhere else (`../posture.md`).

## `Config.get()` / `InstrumenterConfig.get()` in advice

- **Flag:** a config lookup on an advised call, including one buried in a helper that the advice
  calls; follow the helpers.
- **Not when:** it runs in a static initializer, a constructor, or once at install time.
- **Confidence / level:** flag-with-confidence. Level as for #2 in `repeat-work.md`.
- **Fix:** hoist to a `static final` field or compute it once in the constructor. For a feature
  flag, use `@Advice.SkipOn(OnDefaultValue.class)`: compute a `static final boolean` once, return it
  from `@Advice.OnMethodEnter`, and skip the exit advice when disabled. The remaining cost is one
  field read per call, which the JIT can hoist.
- **Why:** this is the most common finding in calibration.

## `@Advice.AllArguments()`

- **Flag:** any use on an advised method.
- **Confidence / level:** flag-with-confidence (a lint could check this). P3, raised for the
  per-span core.
- **Fix:** `@Advice.Argument(value = N)` for the one argument and type needed.
- **Why:** it builds a new `Object[]` that boxes every argument on every call. The array always
  escapes, so escape analysis cannot remove it.

## Per-invocation state

- **Flag:** a `ThreadLocal` used to carry state from `OnMethodEnter` to `OnMethodExit`.
- **Fix:** `@Advice.Local`, which carries the state with no map lookup.

## Reflective `@Advice.Origin`

- **Flag:** a reflective `Method`, `Constructor` or `Executable` origin on a hot advice.
- **Not when:** the origin is a `String` (`@Advice.Origin("#m") String`). ByteBuddy injects it as a
  compile-time constant with no per-call allocation; it is the intended cheap form and widely
  used.
- **Confidence / level:** flag-with-confidence. P3.
- **Fix:** pass the constant `String` pattern (`#m`, `#t`) instead of the reflective object.

## Java Streams in advice

- **Flag:** `stream()` or `IntStream` on an advised call.
- **Not when:** the stream is in a cache-miss body, a length-guarded error path, or cold or
  startup code.
- **Confidence / level:** flag-with-confidence. Level as for #2 in `repeat-work.md`, which also
  has the benchmark numbers.
- **Fix:** a plain `for` loop. A C-style `for`, an enhanced `for`, `forEach` and an `iterator` are
  all equivalent and allocate about nothing.

## `switch(String)`

- **Flag:** a large `switch` on a string in advice; running out of inlining budget is certain
  there. A small switch on a hot path with a constant argument is only a soft alert, and depends
  on the JDK version.
- **Fix, best first:** (1) resolve to a constant `long` id, which folds on any JIT; (2) an
  open-addressed table, which never folds but always inlines with low variance; (3) a plain
  `switch`, which is fine for small dispatch that inlines.
