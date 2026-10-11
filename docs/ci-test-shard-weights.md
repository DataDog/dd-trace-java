# CI test shard weights

CI test jobs are split across parallel shards with `-Pslot=X/Y` (for example `test_inst` runs 8 shards).
Each Gradle project is assigned to exactly one shard by
[`TestShardBalancer`](../buildSrc/src/main/kotlin/datadog/gradle/plugin/ci/TestShardBalancer.kt), which
packs projects by test duration rather than by project count, so that shards finish at about the same time.

## The weights file

[`test-shard-weights.json`](../buildSrc/src/main/resources/datadog/gradle/plugin/ci/test-shard-weights.json)
maps Gradle project paths to the seconds of test execution each project costs per pipeline run in the `test_inst`
job. Only the `weights` object is read; `_comment` and `_metadata` document where the numbers come from.

Projects absent from the file get `TestShardBalancer.DEFAULT_WEIGHT_SECONDS` (18 seconds). The file therefore
only needs to list the projects whose tests are much heavier than average.

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
4. Divide each project's total by the pipeline count, and convert from nanoseconds to seconds.

For example, `jdbc` summed to 548,323.5 seconds over 491 pipelines, giving a weight of `1116.7`.

Keep the projects that clearly dominate the total, update `_metadata` (`generated`, `pipelines_observed`,
`projects_measured`) and the window end date in `_comment`, update the expected entry count in
`TestShardBalancerTest`, then run the buildSrc tests:

```shell
./gradlew :buildSrc:test -PrunBuildSrcTests
```

`TestShardBalancerTest` checks that the instrumentation shards stay balanced, with a max/min load ratio of at most
1.25.

## When to refresh

- After adding, splitting, or renaming a heavy instrumentation module, or one that adds many slow tests.
- When CI Visibility shows the `test_inst` shard durations drifting apart again.

Stale weights only make the balance worse. They never drop test coverage: every project is always assigned to exactly
one shard, and projects missing from the file (including new or renamed ones) fall back to the default weight.
Weights for projects that no longer exist are ignored.
