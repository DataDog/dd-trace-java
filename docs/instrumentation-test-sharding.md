# Instrumentation test sharding

With `-Pslot=X/Y`, main instrumentation tests are assigned by their mean CI duration. Other test
categories, latest dependency tests, muzzle, and non-test verification retain their existing project
hash assignments. Without a valid slot, instrumentation suites run in full.

The scheduler uses the same checked-in scores on every worker. It constructs the complete plan
before applying changed-file filtering, JVM constraints, or skip flags, so independently running
workers agree on every assignment.

## Scheduling units

Projects with at most 120 seconds of measured test work stay together. Heavier projects are split
by their existing test tasks. Tasks exceeding 120 seconds are divided into balanced class groups;
nested classes stay with their enclosing class. Units are placed longest first in the least loaded
slot, with stable identifier and slot-index tie breakers.

A project occupies at most four slots, or the supplied slot count if smaller. This bounds repeated
compilation, dependency setup, and test JVM startup. Class groups reuse the original `Test` task,
preserving its classpath, JVM settings, includes/excludes, fork isolation, and shared resource limits.
Groups assigned to the same slot execute together in that task.

Each project has an owner for unmeasured or newly added tasks. Each split task has one fallback
owner for all classes absent from the scores, including generated classes. New tests therefore run
without requiring a measurement refresh. The default estimate for an unmeasured project/task
remainder is 20 seconds; it is a scheduling estimate, not a CI measurement.

`allTests` and `check` select test dependencies before Gradle constructs the execution graph, avoiding
compilation of unselected test variants through those dependencies. `instrumentationCheck` schedules
the project's non-test checks once and reaches main tests on every slot owning them. Existing custom
task dependencies can still cause additional compilation. A single class cannot be split further.

## Scores and limitations

`buildSrc/src/main/resources/datadog/gradle/plugin/ci/instrumentation-test-durations.json` records
mean suite duration in seconds, keyed by full Gradle task path and original class name. The initial
scores use Java 11 `test_inst` executions on master from October 6 through October 12, 2026: up to 65
observed pipelines per suite, 282 instrumentation projects, and 1,203 suites across 452 tasks. Most
suites have all 65 observations; recently added or conditionally executed suites have fewer.

These are means per observed suite execution, not totals divided by all repository pipelines.
Skipped/missing suites do not contribute artificial zeroes. The reference JVM is shared across the
matrix to keep assignments stable; Java 8-only and newly introduced suites use fallback owners until
measured. The score excludes compilation, container startup, retries beyond recorded suite time,
and worker contention, so balanced modeled loads do not guarantee balanced job wall time.

Class restrictions are additional file exclusions, preserving existing task filters and command-line
test filters. The selected class map and fallback owner are declared task inputs so build-cache
results cannot be reused for a different subset. Deleted classes may leave an empty split task;
those tasks permit no discovered tests.

## Refreshing scores

Use the Datadog MCP's `v2.CIVisibilityTestsApi.aggregateCIAppTestEvents` operation with a fixed window
and this query:

```text
@git.repository.id_v2:"github.com/datadog/dd-trace-java" @git.branch:master @test.configuration.job_name:test_inst @test.configuration.jvm:11 @test.suite:* -@test.name:*
```

Compute `avg(@duration)`, `count`, and `cardinality(@ci.pipeline.id)`. Group by `@test.module` and
`@test.suite`. Sort the groups by average duration descending. Use limits covering the complete
inventory (the initial query used 2,000 modules and five suites per module), check response warnings
and status, and partition the query if a limit is reached. A bounded top-N response is not a complete
measurement inventory.

Export the result as:

```json
{
  "from": "2026-10-06T00:00:00Z",
  "to": "2026-10-13T00:00:00Z",
  "query": "the query above",
  "meta": {"status": "done"},
  "rows": [
    {
      "module": "example_test_TEST-example.ClientTest.xml",
      "className": "example.ClientTest",
      "meanSeconds": 42.5,
      "pipelines": 65
    }
  ]
}
```

Get `className` from `@test.suite`, not the filename: collected filenames may be truncated and
contain a hash. Convert duration from nanoseconds to seconds before exporting.

```shell
python3 tooling/update_instrumentation_test_durations.py --input /tmp/ci-suite-means.json
./gradlew :buildSrc:test -PrunBuildSrcTests --tests 'datadog.gradle.plugin.ci.*'
```

For a configuration experiment, `-PinstrumentationTestDurationsFile=/path/to/scores.json` overrides
the bundled scores. Every worker must use the same file and revision. Normal CI needs no Datadog
credentials or live API request during a build.
