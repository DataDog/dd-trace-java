# Benchmark validity

Checks J12, J15 and J16. Load when the diff touches `src/jmh/`, when the PR text or commits cite
benchmark results, or when Step 1c lists a required benchmark. These check the evidence a change
ships, not production hot paths.

## J12 Profile-dependent dispatch in a JMH benchmark

- **Flag:** a Map or Set JMH benchmark whose measured operation dispatches on a key type that is
  not final, or reaches a strategy or comparator interface with several loaded implementations,
  when its `@Setup` does not pollute the type profile.
- **Not when:** the receiver at the dispatch site under review is statically known: the key is a
  final class (`String`) or an exact type from an allocation, and the call dispatches on the key.
  A final key type doesn't cover a call through a comparator or strategy interface, such as a
  `TreeMap`'s `Comparator.compare`. C2 then devirtualizes without the profile,
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

## J15 Required benchmark results missing or stale

- **Flag:** Step 1c lists required benchmarks, and the PR description has no `## Benchmarks` section
  whose `benchmark-pr` marker names every one of them; or the marker's `head` is not the PR's head
  and a mapped file changed after it.
- **Not when:** the PR carries the `tag: no perf impact` label and its description says why (a
  comment-only or rename-only change, for example). Check that the reason fits the diff.
- **Confidence / level:** flag-with-confidence. P2.
- **Fix:** run `tooling/benchmark_pr.py run` and paste its output into the description.
- **Why:** the mapped code is where the tracer's overhead is measured. A change there without
  before/after numbers is unmeasured by default, whatever it claims.

## J16 Change contradicts a benchmark's recorded findings

- **Flag:** a change that goes against a conclusion recorded in a required benchmark's **Findings**:
  for example, replacing a `forEach` with a cursor for speed when the findings say `forEach` is the
  faster full walk, or wrapping a cursor in an iterator on a path the findings say allocates that
  way.
- **Not when:** the PR's own benchmark results show the conclusion no longer holds, for example on a
  newer JDK. Then suggest updating the findings instead.
- **Confidence / level:** flag-as-measure. P3.
- **Fix:** follow the finding, or include results that overturn it and update the Findings section.
- **Why:** findings record what was measured, under stated conditions. They are evidence, not
  current truth: weigh them by their date, JDK and settings, and treat micro results as directional
  (macro is the acceptance test).

### The Findings convention

A benchmark class whose results inform design keeps them at the top of its Javadoc, so a review can
use them without rerunning anything:

```java
/**
 * <h2>Findings</h2>
 *
 * <ul>
 *   <li>{@code forEach} is the fastest full walk, 5-12% ahead of a cursor with a monomorphic
 *       consumer.
 * </ul>
 *
 * <p>Measured 2026-11-13, MacBook M1, Zulu 17, 3 forks, 8 threads, {@code -prof gc}.
 * ...
 */
```

Lead with conclusions, stated as relative claims ("A is 2x B"), which survive drift better than
absolute numbers; then the conditions. Production code can point at the benchmark that measures it
with `Measured in {@code SomeBenchmark}` in its Javadoc (`{@code}`, since main sources can't link to
the `jmh` source set).
