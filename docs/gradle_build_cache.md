# Gradle build cache

Use this guide to investigate cache reuse, incorrect outputs, and CI performance.
Check [settings.gradle.kts](../settings.gradle.kts) and [.gitlab-ci.yml](../.gitlab-ci.yml)
at the pipeline's commit for its configuration.

## Controls

Set variables when starting a **new pipeline** (job retries keep their pipeline's configuration).

| Need                  | Action                                                                                                                                                        |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Force fresh execution | Set `SKIP_BUILD_CACHE=true` to bypass task caches and up-to-date reuse. See [fresh CI execution](how_to_work_with_gradle.md#forcing-fresh-ci-task-execution). |
| Isolate S3 entries    | Set `S3_BUILD_CACHE_PREFIX`, e.g. `v1/investigate-2163/x86_64/linux/`. It replaces the entire prefix and must be non-empty.                                   |
| Check the destination | Inspect `S3_BUILD_CACHE_BUCKET` and `S3_BUILD_CACHE_REGION`.                                                                                                  |

| Pipeline                        | Default prefix                                     |
| ------------------------------- | -------------------------------------------------- |
| PR, branch, nightly, and master | `v1/<architecture>/<os>/`                          |
| Tag/release                     | `v1/release/<CI_PIPELINE_ID>/<architecture>/<os>/` |

Non-tag pipelines share entries across jobs and pipelines. Release jobs share within
one pipeline.

Changing prefixes leaves local caches and old S3 entries intact. `clean` removes task
outputs, not cache entries. `--refresh-dependencies` refreshes dependency resolution,
not task-output caches.

## Understand reuse

- Gradle caches declared task outputs, including eligible test results. Keys fingerprint
  the implementation and declared inputs; different commits can share matching outputs.
- Gradle checks the local cache before S3. In CI, the local cache is `workspace/build-cache`.
  S3 requires both `CI` and `GITLAB_CI`; ordinary local builds do not use it.
- Dependency downloads, GitLab cache archives, and Gradle's configuration cache are separate.

| Task outcome            | Meaning                                                                                        |
| ----------------------- | ---------------------------------------------------------------------------------------------- |
| `FROM-CACHE`            | Outputs restored from the local cache or S3. Check `S3 cache` in CI job logs for more details. |
| `UP-TO-DATE`            | Existing workspace outputs reused.                                                             |
| `SKIPPED` / `NO-SOURCE` | No execution needed; not a cache hit.                                                          |

## Read cache statistics

Search the main script in a GitLab job log for `S3 cache`, near the end of each Gradle run.
For example, `reads: 100, hits: 90` means a 90% hit rate and 10 misses.

| Measure          | Meaning                                                                                                                                                    |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| S3 hit rate      | Total `hits` divided by total `reads`.                                                                                                                     |
| S3 misses        | Total `reads` minus total `hits`.                                                                                                                          |
| Writes           | Store attempts, which can repeat the same key.                                                                                                             |
| Task restoration | `FROM-CACHE` tasks divided by Gradle's total actionable tasks; includes local and S3 hits.                                                                 |
| Test restoration | Cached `Test` tasks divided by cached plus executed `Test` tasks. Exclude `UP-TO-DATE`, `SKIPPED` and `NO-SOURCE`; these are task counts, not test counts. |

Check individual task outcomes: compilation can hit the cache while tests still execute.
Add counters before calculating rates, grouping jobs by family, architecture and JVM.
Keep Gradle invocations separate, and record transfer bytes and read/write times.
In a read summary, omitted `hits` means zero; omitted `reads` means all lookups hit.
If no summary appears, try `--info` and check cache and statistics settings.

## Investigate a problem

| Symptom                                                                     | Check                                                                                                                                              |
| --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Unexpected misses                                                           | Cache flags, prefix, platform, changed inputs, and the task's cacheability. New namespaces cause expected misses.                                  |
| S3 errors                                                                   | Credentials, bucket/region, permissions, and network errors. A green job does not prove S3 access.                                                 |
| Incorrect cached output                                                     | Compare with an uncached run. Isolate suspect entries while fixing missing inputs or outputs that vary with identical inputs.                      |
| Failures involving generated classes, resources, or instrumentation indexes | Use `SKIP_BUILD_CACHE=true` to regenerate and repackage outputs from `build` or `build_tests` in the failing job.                                  |
| Native smoke-test failures                                                  | Use `SKIP_BUILD_CACHE=true` to rebuild the executable in the failing job. Check that task inputs track changes to the toolchain and builder image. |
| Hits without faster CI                                                      | Transfers, configuration time, tasks that still execute, and the pipeline's critical path.                                                         |

Compare task inputs in [Build Scans](how_to_work_with_gradle.md#build-scans-develocity)
or [compare their fingerprints in logs](#compare-fingerprints-in-logs).

S3 misses can also mean HTTP 403 (access denied) or an entry larger than the plugin's
`maximumCachedObjectLength`. Use `--info` to check the key and reason before assuming
an entry is missing. 

> [!NOTE]
> This build allows entries up to `256 MiB` so smoke application builds can be stored and restored.

When entries that should match do not, check 
* build logic, 
* dependencies, 
* JVM/toolchains, 
* flags, 
* platform, 
* container digests,
* file order,
* generated files. 

Timestamps and absolute paths can cause unnecessary misses while missing inputs can cause incorrect hits.

## Choose input fingerprints

Choose the input declaration according to what the task consumes. A file's extension alone
does not determine its fingerprint: for example a JAR file can be either declared as an 
input file or a runtime classpath.

| Declaration                  | Use when                                                                              | Changes that invalidate the input                                                                                |
| ---------------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `@InputFile` / `@InputFiles` | Exact file contents matter, such as a checksum, signed archive or configuration file. | File bytes and the paths selected by `@PathSensitive`. ZIP metadata is part of the bytes.                        |
| `@Classpath`                 | The task executes or inspects JVM classes and resources, including a Java agent.      | Class and resource paths and contents, after `normalization.runtimeClasspath` rules, plus classpath entry order. |
| `@CompileClasspath`          | Only the Java API visible to compilation matters.                                     | API changes. Method bodies, private members and resources are ignored.                                           |
| `@Input`                     | A value affects execution, such as a version, flag or immutable container digest.     | The value changes.                                                                                               |

In a custom task or nested input object, declare properties such as:

```kotlin
@get:InputFile
@get:PathSensitive(PathSensitivity.NONE)
abstract val distributionArchive: RegularFileProperty

@get:Classpath
abstract val tracerJar: RegularFileProperty

@get:Input
abstract val tracerVersion: Property<String>
```

> [!NOTE]
> About [path sensitivity](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/PathSensitivity.html)
> * `PathSensitivity.NONE` removes paths from the fingerprint while preserving file contents.
> * `PathSensitivity.RELATIVE` includes filenames and subdirectory paths relative to each
>   declared input directory, excluding the path to that directory. For input directory
>   `/checkout/src`, the file `/checkout/src/pkg/Foo.java` contributes `pkg/Foo.java`.
> * `PathSensitivity.ABSOLUTE` (default) includes absolute paths in the fingerprint, so changing
>   those paths prevents cache reuse.

A task that embeds absolute paths in its outputs also needs reproducible outputs before
its cache can be shared.

[`Classpath`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/Classpath.html) 
uses Gradle's built-in runtime classpath normalization. Keep in mind, that
entry order matters for classpath. The normalization of the classpath allow to ignore
files or selected `.properties` ignores these when fingerprint are computed.
Note, the `@Classpath` tracks all resources on the classpath, i.e. classes, text files, 
`.classdata` (renamed `.class`), etc.

For compilation classpath consider [`CompileClasspath`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/CompileClasspath.html)
which has different change detection semantics.

### Normalize runtime inputs

Gradle uses runtime classpath normalization to ignore selected differences when comparing
inputs or calculating cache keys. The rules need to be configured in the project owning the task. 
The `dd-trace-java` ignores generated `.version` files in every project:

```kotlin
normalization {
  runtimeClasspath {
    ignore("**/*.version")
  }
}
```

Matching resources (declared in `ignore(...)`) won't participate in `@Classpath` fingerprints.
But `@InputFile` fingerprints are not affected. Keep exclusions
narrow and prefer tracking any excluded information that still affects 
execution separately by scope, file, convention, whole repo.

By default, Gradle normalises `.properties` files, ignoring comments, whitespace and key order.
Use [`properties { ... }`](https://docs.gradle.org/current/javadoc/org/gradle/normalization/RuntimeClasspathNormalization.html)
to ignore selected keys, or [`metaInf { ... }`](https://docs.gradle.org/current/javadoc/org/gradle/normalization/MetaInfNormalization.html)
to ignore manifest attributes or all `META-INF` contents.

> [!TIP]
>
> `dd-trace-java`'s generated `.version` files contain the project version followed by
> `~<SHA>`, for example `1.69.0-SNAPSHOT~<SHA>`. The rule above excludes that file.
> When the base version affects the task, not the commit hash, the `project.version` 
> is used instead tracked as a tracked `@Input`.

## Diagnose fingerprints in logs

Start with the earliest unexpected miss in the dependency chain. Changed outputs can
invalidate downstream tasks.

### 1. Capture comparable runs

Run before the change, then repeat after the change with output redirected to `after.log`.
Keep the task, JVM, dependencies and flags comparable:

```shell
./gradlew :dd-smoke-tests:quarkus:clean :dd-smoke-tests:quarkus:test \
  --build-cache --info --console=plain -Dorg.gradle.caching.debug=true \
  > before.log 2>&1
```

- Use `clean` to remove outputs so Gradle can restore them from the cache.
- Omit `--rerun-tasks` when checking restoration.
- Local runs compare task inputs. Check CI logs to verify S3 access.

### 2. Find the same task in both logs

Search for cache keys, fingerprints and reasons for execution:

```shell
rg -n --max-columns 300 --max-columns-preview \
  'Build cache key|Appending .*implementation|Appending input|Appending output|Caching disabled|not up-to-date' \
  before.log after.log
```

Locate the task path and compare its fingerprint block in each log. For example
(hashes and property prefixes vary by task):

```text
Appending implementation to build cache key: <task-class>@<implementation-hash>
Appending input value fingerprint for '...tracerVersion' to build cache key: <version-hash>
Appending input value fingerprint for '...neededResourcePaths' to build cache key: <resource-names-hash>
Appending input file fingerprints for '...tracerJar' to build cache key: <jar-hash> - CLASSPATH{...}
Appending output property name to build cache key: <output-name>
Build cache key for task ':dd-smoke-tests:quarkus:test' is <task-key>
```

The search preview truncates long lines. Open the logs at those line numbers to compare
complete file fingerprints.

### 3. Follow the first difference

Compare implementation hashes first, then output property names and individual inputs:

| Log evidence                              | Next check                                                                                                      |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Implementation or action hash differs     | Compare Gradle, plugin and build-logic versions before inspecting file changes.                                 |
| Output property name differs              | Check the task's output declarations; output names contribute to the key.                                       |
| Named input value hash differs            | Compare that property's resolved value, such as a version, flag or digest.                                      |
| File input hash differs                   | Compare normalized paths and per-file hashes, then inspect the changed file contents.                           |
| `ABSOLUTE_PATH`                           | Compare checkout locations because absolute paths contribute to the fingerprint.                                |
| `CLASSPATH`                               | Compare classpath order and tracked archive contents. ZIP metadata is ignored.                                  |
| `No history is available`                 | Gradle has no local up-to-date record. A cache lookup can still succeed.                                        |
| `Caching disabled for task`               | Resolve the reported reason before expecting restoration.                                                       |
| Final task keys match, but no restoration | Check the namespace, read settings, permissions and entry-size limit, and whether the producer stored that key. |

Use the normalizer label to assess path sensitivity. Printed file locations can be absolute
even when the fingerprint ignores them.

<details>
<summary>Example: matching tracerVersion, different tracerJar</summary>

JMXFetch's `metricconfigs.txt` listed the same filenames in different orders across builds.
Sorting the list made the resource reproducible. Its text contents affected the `@Classpath`
fingerprint even though ZIP metadata was already ignored.

</details>

See Gradle's [cache debugging guide](https://docs.gradle.org/current/userguide/build_cache_debugging.html).

## Inspect a cache entry

With S3 read access, inspect an object's size, last-modified time and metadata using its full key:

```shell
aws s3api head-object --bucket '<bucket>' --key '<prefix><cache-key>'
```

Optional [plugin metadata](https://github.com/burrunan/gradle-s3-build-cache/tree/v1.9.11#s3-metadata)
includes the task path (`identity`), execution time in milliseconds (`executiontime`),
build ID (`buildinvocationid`), Gradle version and operating system. S3 lowercases these keys.
Use the build ID to help trace the producer, and logs to count reads and misses.

## Compare correctness and performance

Use the same commit, jobs, platform, JVMs, resolved dependencies, and other flags:

| Run      | Configuration                                                                        |
| -------- | ------------------------------------------------------------------------------------ |
| Uncached | `SKIP_BUILD_CACHE=true`                                                              |
| Cold S3  | Cache enabled, unique prefix, empty local task cache, clean outputs                  |
| Warm S3  | Cache enabled, same prefix after the cold run, empty local task cache, clean outputs |

Check each job's local cache when measuring S3 reuse. Later jobs in a cold pipeline
can reuse earlier jobs' entries. Scheduled lock refreshes can change dependencies at
the same commit. Master is a baseline, but differing code/jobs limit comparison.

Check artifacts and timing as well as job status:

- **JARs:** `validate_build` compares `build` artifacts with one candidate using the pipeline's
  cache settings. The candidate may reuse workspace outputs.
  Read `check_reports/<module>/jardiff/`; failed comparisons preserve JARs in
  `check_reports/jar-comparison-artifacts/{reference,candidate}/`.
  Check dependency version stamps. Compare runs with and without caching to check cache effects.
  #12604 adds a cached/uncached validation matrix; `SKIP_BUILD_CACHE=true` forces both variants uncached.
- **Tests:** compare JUnit/Muzzle names, counts, outcomes, and shard coverage in
  `results/`, `reports/`, or `check_reports/`. Timing fields can differ.
  Cached tests restore previous results. Check collection logs for missing reports.
- **Time:** compare actual job duration and the pipeline's critical path.
  Record queue time separately. Break out runner setup, checkout, GitLab cache restoration,
  Gradle configuration and tasks, report collection, and artifact upload. Compare similarly warm
  caches for each job family and architecture. The plugin's summed
  time-saved estimate does not account for parallel execution.

## Task changes and issue reports

Follow [Task Inputs and Outputs](how_to_work_with_gradle.md#task-inputs-and-outputs):
declare all inputs that affect results and outputs consumed downstream. Track external
state such as container digests; keep outputs reproducible across checkouts.

Keep [secrets out of cached outputs](how_to_work_with_gradle.md#secrets-in-cached-outputs).
Input hashing and log masking do not sanitize cached files or captured test output.

When asking for help, include pipeline/job links, commit, task, platform, JVMs,
flags/prefix, cache keys, scans/logs, and artifact differences. Remove sensitive values.
