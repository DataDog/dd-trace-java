# Benchmark validity

Check J12. Load when the diff touches `src/jmh/`, or when the PR text or commits cite benchmark
results. These check the evidence a change ships, not production hot paths.

## J12 Profile-dependent dispatch in a JMH benchmark

- **Flag:** a Map or Set JMH benchmark whose measured operation dispatches on a key type that is
  not final, or reaches a strategy or comparator interface with several loaded implementations,
  when its `@Setup` does not pollute the type profile.
- **Not when:** the receiver type is known at the benchmark's own call site: the key is a final
  class (`String`) or an exact type from an allocation. C2 then devirtualizes without the profile,
  and polluting it only adds setup allocation. One key class per benchmark is normal and correct;
  a production `Map<String, V>` call site sees one type too.
- **Confidence / level:** flag-as-measure. P3.
- **Fix:** call `BenchmarkUtils.warmUpHashDispatch(Blackhole)` from `@Setup(Level.Trial)`. Use the
  trial level, not per iteration: it runs 50,000 allocation-heavy passes.
- **Why:** JMH forks a fresh JVM per benchmark method, so each one is compiled in a process that
  has only ever run that one method. Shared `hashCode()` / `equals()` / `compareTo` call sites are
  then much cleaner than in production, where erasure makes them serve every map in the
  application.
- **Example:** in a one-off measurement, a benchmark whose strategy interface had four
  implementations fell from 37.9 to 18.1 M ops/s once its profile was polluted. `String`-keyed
  map benchmarks in the same module did not change, and LogCompilation confirmed their dispatch
  was resolved statically.

## Escape-analysis claims for objects that span I/O

- **Flag:** a claim that a per-span scope or wrapper object costs nothing, backed by a benchmark
  that does no I/O. Treat the claim as unverified.
- **Not when:** the benchmark includes realistic I/O inside the scope.
- **Confidence / level:** flag-as-measure. P3.
- **Why:** in a tight microbenchmark loop, C2 inlines everything and scalar-replaces the object.
  In production, scopes almost always wrap I/O, and C2 cannot inline through native or blocking
  I/O. The object lives across the call, escapes, and allocates.
