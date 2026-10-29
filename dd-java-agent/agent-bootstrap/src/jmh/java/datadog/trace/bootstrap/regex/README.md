# RegexOpsBenchmark

JMH comparison of each `RegexOps` method against the JDK call it replaces. Each benchmark name has the form
`<op>_<jdk|ops>_<literal|dynamic>`, and each one runs with `input=short` (~40 chars) and `input=long` (~2000 chars).

- `literal`: the same regex reference on every call. This is the cache hit path once the regex has been admitted on its second sighting.
- `dynamic`: `new String(regex)` on every call. The cache never admits it, so this measures the miss-path overhead. The JDK variant pays for the same allocation.
- `splitFastpath_*`: single-char `split(",")`. `RegexOps` must delegate to the JDK fastpath here, so it should match the JDK.

## Cache design

- `RegexCache` is a fixed 1024-slot direct-mapped table indexed by `System.identityHashCode(regex)` mixed with the flags.
  Lookups compare the regex by reference and the flags by value, so the hit path never calls `String.hashCode` or `equals`.
- A pattern is admitted on the second sighting of the same reference. A separate candidate table records first sightings.
  Per-request dynamic strings are fresh objects, so they are never admitted.
- Regexes longer than 1024 chars are compiled directly and never stored, so neither table retains large request-derived strings.
- Entries are immutable with final fields, so the racy table writes are benign. Failed compiles never touch the tables, and
  `Matcher` is never cached.
- `RegexOps` mirrors the JDK implementation of each rewritten call with `Pattern.compile` swapped for `RegexCache.get`, and keeps
  the `String.split` single-char fastpath by delegating to the JDK when it applies.

## Run

On a quiet machine:

```shell
# All of it: 35 methods x 2 inputs, 2 forks, 3x1s warmup + 5x1s measurement each (about 20 minutes)
./gradlew :dd-java-agent:agent-bootstrap:jmh -Pjmh.includes=RegexOpsBenchmark -PtestJvm=17

# One operation
./gradlew :dd-java-agent:agent-bootstrap:jmh -Pjmh.includes='RegexOpsBenchmark.split_' -PtestJvm=17

# With the allocation profiler
./gradlew :dd-java-agent:agent-bootstrap:jmh -Pjmh.includes=RegexOpsBenchmark -Pjmh.profilers=gc
```

Results go to `dd-java-agent/agent-bootstrap/build/results/jmh/`. The `-jmh.jar` is not self-contained (it only references
sibling jars through its manifest `Class-Path`), so use the Gradle task, not `java -jar`.

For a quick check that everything runs (numbers mean nothing), use a throwaway init script that shortens iterations:

```groovy
// /tmp/jmh-smoke.gradle
allprojects {
  if (path == ':dd-java-agent:agent-bootstrap') {
    afterEvaluate {
      jmh { includes = ['RegexOpsBenchmark']; warmupIterations = 0; iterations = 1; timeOnIteration = '20ms'; fork = 1 }
    }
  }
}
```

```shell
./gradlew :dd-java-agent:agent-bootstrap:jmh -I /tmp/jmh-smoke.gradle
```
