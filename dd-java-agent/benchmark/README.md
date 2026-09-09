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
- `instrumentation-only.jar`: only indexed `InstrumenterModule` classes packed into early-load and
  exact target-system shards; all other classes remain individual entries;
- `semantic-common.jar`: all common classes packed, with non-module classes grouped into Byte Buddy,
  agent, telemetry, communication, and fallback families before chunking;
- `load-order-common.jar`: all common classes packed using one non-module load-order group followed
  by the same instrumenter shards, preserving the pre-semantic production strategy as a control;
- `production.jar`: the actual production `shadowJar`, packed from the checked-in chunk plan;
- `stored-{10,25,50,75,100}.jar`: the corresponding load-order prefix stored without compression;
- `packed-{64,256,1024}.jar`: compact build-time index plus independently compressed load-order
  chunks of the indicated class count;
- `packed-all.jar`: the same compact index with one full common-class chunk.
- `packed-64-dedup.jar`: the 64-class layout without duplicate individual entries.

Packed variants other than `packed-64-dedup` retain individual entries as a compatibility control.
The deduplicated and production variants serve packed class resource streams directly. Production
packing applies only to selected `.classdata` entries; ordinary agent resources remain unchanged.
Production chunks place non-module classes into deterministic Byte Buddy, agent, telemetry,
communication, and fallback families while preserving load order within each family. Indexed
`InstrumenterModule` classes are sharded by their exact target-system mask; modules marked for early
load share a separate shard regardless of their target systems. This keeps disabled subsystems'
chunks unopened while correctly handling modules that apply to more than one product.
Chunks stay cached during synchronous bootstrap because classes may be defined by both agent class
loaders. At the end of bootstrap they are released, after which an eight-chunk LRU bounds retention
while preserving locality for instrumentations loaded during application startup.

The production chunk layout is committed in `metadata/common-classdata-plan.txt`. It records the
global chunk number, semantic or product shard, and class order, so two builds with the same plan
produce the same layout. `shadowJar` consumes this plan directly and validates product shards
against the current `instrumenter.index`.

After reviewing a fresh profile in `metadata/common-classdata.txt`, regenerate the plan with:

```shell
./gradlew :dd-java-agent:generateCommonClassDataPlan
```

The GitLab `verify-common-classdata-plan` job runs this verification on development branches and
skips `master`, release branches, tags, and dependency-cache population pipelines. It derives a
candidate from the committed profile and current instrumenter index, compares it with the committed
plan, and reports added, removed, reassigned, or moved classes. It deliberately does not launch a
timing-sensitive class-load profile on every PR. Profile collection and benchmark review remain the
evidence-gathering step; plan verification is the reproducible build gate.

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
