# Benchmark validity

Check J12. Load when the diff touches `src/jmh/`, or when the PR text or commits cite benchmark
results. These are methodology checks on submitted evidence, not production hot-path findings.

## Checks

- **J12 — Benchmark validity: is the dispatch under test actually profile-dependent?**
  *(methodology check on a submitted benchmark, not a production hot-path finding — refines the
  flag-as-measure posture in J2)*. JMH forks a fresh JVM per benchmark method, so each arm is
  compiled in a process that has only ever executed that one arm — leaving shared
  `hashCode()`/`equals()`/`compareTo` call sites unrealistically clean compared with production,
  where erasure makes them serve every map in the application. This only affects the measurement
  when the receiver type is **not statically deducible at the benchmark's own call site**: if the
  key is a final class (`String`) or an exact type from an allocation, C2 devirtualizes on that
  proof and never consults the profile, so pollution is a no-op that only adds setup allocation.
  Note that one key class per benchmark is normal and correct — a production `Map<String, V>` call
  site is monotyped too; variety at the caller is not what makes the internal site megamorphic.
  **flag-as-measure** when a Map/Set JMH benchmark's measured operation dispatches on a non-final
  key type, or reaches a strategy/comparator interface with several loaded implementors, and its
  `@Setup` does not pollute — fix: call `BenchmarkUtils.warmUpHashDispatch(Blackhole)` from
  `@Setup(Level.Trial)` (trial level, not per-iteration: it runs 50,000 allocation-heavy passes). A
  one-off measurement made while working on #12298 (not recorded in a committed benchmark) showed
  both sides — `FlatHashtableIteratorBenchmark`'s four-implementor strategy dispatch fell 37.9 →
  18.1 M ops/s when its profile was poisoned, while the `String`-keyed map benchmarks in the same
  module were unaffected, with LogCompilation confirming the dispatch resolved statically.

## Patterns

**EA claims for scope/wrapper objects spanning I/O — treat as unverified.** A microbenchmark
tight-loop can show zero allocation for a scope or wrapper object because C2 inlines through
everything and scalar-replaces it. In production, scopes almost always wrap I/O — and C2 cannot
inline through native/blocking I/O boundaries. The object's lifetime extends across the call, it
escapes, and it allocates. A benchmark without I/O-wrapping is not a credible check. Treat EA
claims about per-span scope objects as unverified unless the benchmark explicitly includes
realistic I/O usage.
