# Gradle build cache

Use this guide to investigate cache reuse, incorrect outputs, and CI performance.
S3-specific sections describe the [S3 rollout](https://github.com/DataDog/dd-trace-java/pull/12604).
Check [settings.gradle.kts](../settings.gradle.kts) and [.gitlab-ci.yml](../.gitlab-ci.yml)
at the pipeline's commit for its configuration.

## Controls

Set variables when starting a **new pipeline**; job retries keep their configuration.

| Need | Action |
| --- | --- |
| Force fresh execution | Set `SKIP_BUILD_CACHE=true` to bypass task caches and up-to-date reuse. See [fresh CI execution](how_to_work_with_gradle.md#forcing-fresh-ci-task-execution). |
| Isolate S3 entries | Set `S3_BUILD_CACHE_PREFIX`, e.g. `v1/investigate-2163/x86_64/linux/`. It replaces the entire prefix and must be non-empty. |
| Check the destination | Inspect `S3_BUILD_CACHE_BUCKET` and `S3_BUILD_CACHE_REGION`. |

| Pipeline | Default prefix |
| --- | --- |
| PR, branch, nightly, and master | `v1/<architecture>/<os>/` |
| Tag/release | `v1/release/<CI_PIPELINE_ID>/<architecture>/<os>/` |

Non-tag pipelines share entries across jobs and pipelines. Release jobs share within
one pipeline; preserve that isolation in overrides.

Changing prefixes leaves local caches and old S3 entries intact. `clean` removes task
outputs, not cache entries. `--refresh-dependencies` refreshes dependency resolution,
not task-output caches.

## Understand reuse

- Gradle caches declared task outputs, including eligible test results. Keys fingerprint
  the implementation and declared inputs; different commits can share matching outputs.
- Gradle checks the local cache before S3. In CI, the local cache is `workspace/build-cache`.
  S3 requires both `CI` and `GITLAB_CI`; ordinary local builds do not use it.
- Dependency downloads, GitLab cache archives, and Gradle's configuration cache are separate.

| Task outcome | Meaning |
| --- | --- |
| `FROM-CACHE` | Outputs restored. Check S3 transfer logs/statistics to distinguish remote from local hits. |
| `UP-TO-DATE` | Existing workspace outputs reused. |
| `SKIPPED` / `NO-SOURCE` | No execution needed; not a cache hit. |

## Investigate a problem

| Symptom | Check |
| --- | --- |
| Unexpected misses | Cache flags, prefix, platform, changed inputs, and the task's cacheability. New namespaces cause expected misses. |
| S3 errors | Credentials, bucket/region, permissions, and network errors. A green job does not prove S3 access. |
| Incorrect cached output | Compare with an uncached run. Isolate suspect entries while fixing missing inputs or outputs that vary with identical inputs. |
| Hits without faster CI | Transfers, configuration time, tasks that still execute, and the pipeline's critical path. |

Compare task inputs in [Build Scans](how_to_work_with_gradle.md#build-scans-develocity).
Muzzle jobs disable scans; use logs instead. For detailed fingerprints:

```shell
./gradlew :dd-trace-api:clean :dd-trace-api:test \
  --build-cache --info -Dorg.gradle.caching.debug=true
```

Replace the module/task as needed. Run twice and compare keys and input hashes.
Cleaning outputs allows restoration; omit `--rerun-tasks` when checking hits.
Local runs diagnose inputs, but do not verify CI's S3 access.

Check build logic, dependencies, JVM/toolchains, flags, platform, container digests,
and generated files. Timestamps and absolute paths can cause unnecessary misses;
missing inputs can cause incorrect hits. See Gradle's
[cache debugging guide](https://docs.gradle.org/current/userguide/build_cache_debugging.html).

## Compare correctness and performance

Use the same commit, jobs, platform, JVMs, resolved dependencies, and other flags:

| Run | Configuration |
| --- | --- |
| Uncached | `SKIP_BUILD_CACHE=true` |
| Cold S3 | Cache enabled, unique prefix, empty local task cache, clean outputs |
| Warm S3 | Cache enabled, same prefix after the cold run, empty local task cache, clean outputs |

Check each job's local cache when measuring S3 reuse. Later jobs in a cold pipeline
can reuse earlier jobs' entries. Scheduled lock refreshes can change dependencies at
the same commit. Master is a baseline, but differing code/jobs limit comparison.

Check artifacts and timing as well as job status:

- **JARs:** `validate_build` compares `build` artifacts with cached and uncached rebuilds.
  Read `check_reports/<module>/jardiff/`; failed comparisons preserve JARs in
  `check_reports/jar-comparison-artifacts/{reference,candidate}/`.
  Check dependency version stamps. `SKIP_BUILD_CACHE=true` makes both validators uncached.
- **Tests:** compare JUnit/Muzzle names, counts, outcomes, and shard coverage in
  `results/`, `reports/`, or `check_reports/`. Timing fields can differ.
  Cached tests restore previous results. Check collection logs for missing reports.
- **Time:** compare actual job duration and the pipeline's critical path.
  Separate queue, artifact transfer, and configuration time. The plugin's summed
  time-saved estimate does not account for parallel execution.

## Task changes and issue reports

Follow [Task Inputs and Outputs](how_to_work_with_gradle.md#task-inputs-and-outputs):
declare all inputs that affect results and outputs consumed downstream. Track external
state such as container digests; keep outputs reproducible across checkouts.

Keep [secrets out of cached outputs](how_to_work_with_gradle.md#secrets-in-cached-outputs).
Input hashing and log masking do not sanitize cached files or captured test output.

When asking for help, include pipeline/job links, commit, task, platform, JVMs,
flags/prefix, cache keys, scans/logs, and artifact differences. Remove sensitive values.
