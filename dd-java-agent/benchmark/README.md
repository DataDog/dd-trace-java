# Java agent microbenchmarks

Run the class-data archive microbenchmark with:

```shell
./gradlew :dd-java-agent:benchmark:jmh \
  -Pjmh.includes=ClassDataLoadingBenchmark \
  -Pjmh.profilers=gc
```

The preparation task launches a minimal application with the assembled agent, records the
`.classdata` classes loaded before application main, and creates comparable agent jars:

- `baseline.jar`: individually deflated entries, matching the current layout;
- `production.jar`: the actual production `shadowJar`, packed from the checked-in manifest;
- `stored-{10,25,50,75,100}.jar`: the corresponding load-order prefix stored without compression;
- `packed-{64,256,1024}.jar`: compact build-time index plus independently compressed load-order
  chunks of the indicated class count;
- `packed-all.jar`: the same compact index with one full common-class chunk.
- `packed-64-dedup.jar`: the 64-class layout without duplicate individual entries.

Packed variants other than `packed-64-dedup` retain individual entries as a compatibility control.
The deduplicated and production variants serve packed class resource streams directly. Production
packing applies only to selected `.classdata` entries; ordinary agent resources remain unchanged.
Production chunks keep non-module classes in load order, but place indexed `InstrumenterModule`
classes into deterministic shards by their exact target-system mask. Modules marked for early load
share a separate shard regardless of their target systems. This keeps disabled products' module
chunks unopened while correctly handling modules that apply to more than one product.
Chunks stay cached during synchronous bootstrap because classes may be defined by both agent class
loaders. At the end of bootstrap they are released, after which an eight-chunk LRU bounds retention
while preserving locality for instrumentations loaded during application startup.

The generated artifacts are under `build/classdata-benchmark`. Artifact discovery also accepts a
`default`, `profiling`, `appsec`, or `tracing-disabled` scenario when its main class is invoked
directly. The JMH
benchmark measures reading 25%, 50%, and 100% of the discovered common classes, both with an
already-open loader and including loader/JAR opening.

Run the fresh-JVM comparison with:

```shell
./gradlew :dd-java-agent:benchmark:classDataStartupBenchmark
```

It performs five warmups followed by thirty randomized runs per layout and prints CSV containing
mean, median, p95, standard deviation, and jar size. Override those counts with
`-Ddatadog.classdata.benchmark.warmups` and
`-Ddatadog.classdata.benchmark.repetitions`. A focused subset can be selected with the comma-separated
`-Ddatadog.classdata.benchmark.layouts` property. Re-run with `-PtestJvm=8`, `17`, and `21` to compare
supported JVMs. Repeated runs use a warm filesystem cache; clearing the operating system page cache
must be done separately when true cold-I/O measurements are required.

Select a product configuration with
`-Ddatadog.classdata.benchmark.scenario=profiling`, `appsec`, or `tracing-disabled` (the default is
`default`).

The artifact and startup main classes accept `default`, `profiling`, `appsec`, and
`tracing-disabled` scenario arguments for direct cross-product experiments. Artifact generation also
accepts a final `profile-only` argument when only the discovered class list is needed. All discovery
and startup measurements stop when the minimal target reaches application `main`;
application-framework readiness is deliberately outside this benchmark's scope.
