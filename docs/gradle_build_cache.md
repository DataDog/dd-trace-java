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

Compare task inputs in [Build Scans](how_to_work_with_gradle.md#build-scans-develocity).
Muzzle jobs disable scans; use logs instead. For detailed fingerprints:

```shell
./gradlew :dd-trace-api:clean :dd-trace-api:test \
  --build-cache --info -Dorg.gradle.caching.debug=true
```

Replace the module/task as needed. Run twice and compare keys and input hashes.
Cleaning outputs allows restoration; omit `--rerun-tasks` when checking hits.
Local runs diagnose inputs, but do not verify CI's S3 access.

S3 misses can also mean HTTP 403 (access denied) or an entry larger than the plugin's
`maximumCachedObjectLength`. Use `--info` to check the key and reason before assuming
an entry is missing. This build allows entries up to 256 MiB so smoke application builds
can be stored and restored.

Check build logic, dependencies, JVM/toolchains, flags, platform, container digests,
and generated files. Timestamps and absolute paths can cause unnecessary misses;
missing inputs can cause incorrect hits. See Gradle's
[cache debugging guide](https://docs.gradle.org/current/userguide/build_cache_debugging.html).

Smoke tests fingerprint the agent JAR through `SmokeAgentArguments.tracerJar` (`@Classpath`).
Runtime-classpath normalisation excludes generated `.version` resources; the project version
is tracked separately. Before restoring cached test results, `verifyAgentJarContents` checks
the packaged version resources against the current version and commit. Its marker tracks their
paths, so adding or removing a version resource invalidates test results. Commit-only changes
can reuse matching results; classes, `.classdata` and other resources remain in the fingerprint.

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
