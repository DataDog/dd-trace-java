# CI test shard weights

CI test jobs are split across parallel shards with `-Pslot=X/Y` (for example `test_inst` runs 8 shards).
Each Gradle project is assigned to exactly one shard by
[`TestShardBalancer`](../buildSrc/src/main/kotlin/datadog/gradle/plugin/ci/TestShardBalancer.kt), which
packs projects by test duration rather than by project count, so that shards finish at about the same time.

## The weights file

[`test-shard-weights.json`](../buildSrc/src/main/resources/datadog/gradle/plugin/ci/test-shard-weights.json)
maps Gradle project paths to the seconds of test execution each project costs per pipeline run in the `test_inst`
job. Only the `weights` object is read; `_comment` and `_metadata` document where the numbers come from.

Projects absent from the file get `TestShardBalancer.DEFAULT_WEIGHT_SECONDS` (20.6 seconds, the mean weight of the
measured projects that are left out of the file). The file therefore only needs to list the projects whose tests are
much heavier than average, but it must not miss any of the heaviest ones: a heavy project that falls back to the
default weight can overload whichever shard it lands in.

Projects are then placed heaviest first (ties broken by project path) into the least loaded shard (ties broken by
lowest shard index). The assignment is computed from all project paths, so it does not depend on which shard is
running, and it does not use hash codes, so it is the same on every JVM and machine.

## Regenerating the weights

Weights come from Datadog CI Visibility test events at the **module** level, over a 7-day window:

```
@git.repository.id_v2:"github.com/datadog/dd-trace-java" @test.configuration.job_name:test_inst @test.configuration.jvm:11
```

1. Aggregate `sum(@duration)` grouped by `@test.module`. Module names come from the uploaded JUnit reports and look
   like `<project name>_<test task>_TEST-<TestClass>.xml`, e.g. `akka-actor-2.5_test_TEST-AkkaActorTest.xml`.
2. Strip the `_<test task>_TEST-<TestClass>.xml` suffix to get the project name, and sum all modules of a project
   (all of its test tasks and test classes). Map the project name to its Gradle project path from
   `settings.gradle.kts`, e.g. `jdbc` to `:dd-java-agent:instrumentation:jdbc`. Watch out for project names that
   contain underscores, such as `spark_2.12`.
3. Count the observed pipelines with `cardinality(@ci.pipeline.id)` over the same query.
4. Divide each project's total by the pipeline count, and convert from nanoseconds to seconds. The result is a mean
   per pipeline, not a median.

For example, `jdbc` summed to 548,323.5 seconds over 491 pipelines, giving a weight of `1116.7`
before its test suites were split into independent CI shard projects.

Measure every project listed in `settings.gradle.kts` one by one (e.g. `@test.module:lettuce-5.0_*`) instead of
reading a single grouped aggregation or guessing family prefixes:

- grouped aggregations can silently return only the first few buckets;
- a prefix that matches nothing (e.g. `aws-sdk*` when the projects are named `aws-java-sdk-*`) means the prefix is
  wrong, not that the project is light.

Keep the projects that clearly dominate the total, and set `DEFAULT_WEIGHT_SECONDS` to the mean weight of the
projects left out. Update `_metadata` (`generated`, `pipelines_observed`, `projects_measured`) and the window end
date in `_comment`, update the expected entry count in `TestShardBalancerTest`, then run the buildSrc tests:

```shell
./gradlew :buildSrc:test -PrunBuildSrcTests
```

`TestShardBalancerTest` checks that every weighted path is a project from `settings.gradle.kts`, that the two
heaviest projects land in different shards, and that the modeled instrumentation shards stay balanced, with a
max/min load ratio of at most 1.25. That ratio is computed from the weights, not measured, see below.

## JDBC suite shards

The JDBC instrumentation keeps one Gradle source module, but its `test_inst` work is exposed through 15 virtual
child projects so the balancer can assign its existing suites to different shards. The child projects delegate to
test tasks in the parent project; they do not copy tests or change dependencies. Local `allTests` and `check` still
run every suite from the JDBC project as before.

Pipeline `144153574` provided the first coverage-complete measurement of the split on JVM 11: 46 suites and 1,814
passing tests. It showed that the remote database suites dominate several tasks. The three regular version tasks
therefore expose `RemoteJDBCInstrumentationV0Test` separately from their remaining suites, and the three remote
forked classes each have their own virtual project. The remaining forked tasks form exhaustive, disjoint groups
across all 22 concrete forked classes; an unweighted catch-all keeps newly added forked classes covered.

The largest modeled JDBC unit after this split is the base version's remote suite at 316.5 s, down 71.7% from the
original 1116.7-second JDBC project. The next largest units are the remote DBM fork at 293.7 s and the old H2 remote
suite at 288.5 s. The checked-in weights come directly from that pipeline's suite durations.

## Known limitation: the heaviest project sets a floor

A project's tests always run in a single shard, and tests run in parallel within a shard. The balancer only adds up
seconds, so it cannot see that a shard dominated by one large project finishes later than a shard with the same total
spread over many small projects. In the first pipeline using this balancer, shards 1/8 and 4/8 ran almost the same
total test time (2312 s and 2292 s), yet took 39 and 24 minutes: shard 1/8 held `jdbc`.

Before the JDBC suite split, the two heaviest projects per pipeline were:

- `:dd-java-agent:instrumentation:jdbc`: 1116.7 s, about 18.6 minutes;
- `:dd-java-agent:instrumentation:lettuce:lettuce-5.0`: 978.1 s, about 16.3 minutes.

After the split, JDBC no longer sets that floor; `lettuce-5.0` is the heaviest remaining project. The slowest shard
can never be faster than the heaviest single project, plus job setup. A project's wall-clock span is also longer
than its summed test time: in that first pipeline, the `jdbc` tests (20.5 minutes of summed test time) spanned 27.6
minutes, starting about 10 minutes into the job, so its shard took 39 minutes. Under the previous hash-based
assignment, the `jdbc` shard was already the slowest one.

The modeled max/min ratio is optimistic whenever one project approaches the target shard time, so treat the 1.25
bound as a check of the model rather than a guarantee for wall-clock time.

The two heaviest projects must not share a shard, otherwise their floors stack. Capping weights only makes the model
look balanced while letting more work pile onto the affected shard. Lowering the floor requires splitting the work
into independently scheduled units, as the JDBC suite shards do above.

## When to refresh

- After adding, splitting, or renaming a heavy instrumentation module, or one that adds many slow tests.
- When CI Visibility shows the `test_inst` shard durations drifting apart again.

Stale weights only make the balance worse. They never drop test coverage: every project is always assigned to exactly
one shard, and projects missing from the file (including new or renamed ones) fall back to the default weight.
After renaming or removing a weighted project, update its entry: `TestShardBalancerTest` fails on weights for paths
that are not projects.
