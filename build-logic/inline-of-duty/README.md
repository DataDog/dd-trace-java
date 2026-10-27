# JIT Inlining Report Gradle plugin

`dd-trace-java.inline-of-duty` captures and analyzes HotSpot compilation decisions during
ordinary Gradle JUnit/Spock tests. It runs entirely on the JVM, with no Python,
LLM, native agent or external analysis service. The root build applies the plugin;
normal tests retain their existing agents, forks and JUnit XML.

## Capture during tests

```sh
./gradlew :utils:time-utils:test -PtestJvm=25 -PinlineOfDuty
```

Use any normal test task or existing parent aggregate. The root `inlineOfDutyReport`
finalizer runs after the selected test workers, including when tests fail. It does
not schedule additional test tasks. Capture forces selected tests to execute
instead of reusing test-result caches; compilation tasks keep their normal cache
behavior. Without `-PinlineOfDuty`, no capture flags are added or worker logs created.
`-PinlineOfDuty=false` also disables capture.

Outputs use Gradle's build-directory convention, including the CI `workspace`
layout:

```text
build/inline-of-duty/captures/<execution>/<task-path>/
  capture.json
  hotspot_pid123.log
build/reports/inline-of-duty/
  index.html       Four totals/percentages and a short refusal list
  details.html     Bounded evidence for the selected contexts
  findings.json    Compact machine-readable export with exact totals
  summary.md       PR-comment-ready counts and percentages
```

Each build execution has a fresh capture identity, including configuration-cache
replays. Skipped tests cannot pull old observations into the current report.
Separate JVMs have independent warmup histories. Child JVMs launched manually by
tests are not automatically captured. Capture is for HotSpot; OpenJ9 is unsupported.
JDK 25 is the initial runtime validation target.

In GitLab, capture paths include `CI_PIPELINE_ID` and `CI_JOB_ID` before the
execution UUID. A test job publishes only its own capture subtree, which prevents
artifact extraction and restored workspace caches from mixing jobs or pipelines.
The manifest also records pipeline/job IDs and the producing job name.

## Analyze existing captures

```sh
./gradlew inlineOfDutyReport -PinlineOfDutyInput=/absolute/path/to/captures
```

This mode schedules no tests and can aggregate several captured runs. Copied logs
are deduplicated by content. No raw-log input is modified. Empty inputs produce a
zero-worker report; an explicit missing input directory fails. Missing worker logs
for a captured test task and malformed/truncated logs fail reporting after writing
diagnostics. A failed test remains a failed Gradle build even when reporting succeeds.

Options:

- `-PinlineOfDutyMinCompilations=3`: minimum completed, installed C2 compilation count at the same call context in one worker for the shortlist
  (default 2, minimum 2).
- `-PinlineOfDutyTop=10`: maximum actionable contexts, diagnostic method rows, and
  decision samples per context in the default HTML/JSON export (default 10, minimum 1).
  Totals and percentages always use the complete analysis. The export records how
  many contexts/methods were omitted. Worker links remain available for every capture.
- `-PinlineOfDutyFullEvidence=true`: explicitly export all contexts, decisions, method
  records and JVM metadata. This can produce large artifacts; the overview still
  shows only the top actionable contexts. Raw logs remain in the producing jobs.
- `-PinlineOfDutyIncludeTestCode=true`: include known test-harness packages and coverage
  probe helpers, which the default tracer view excludes.
- `-PinlineOfDutyOutput=/path/to/report`: choose the report directory (default
  `build/reports/inline-of-duty`).

## Interpret the overview

Each observed callee method signature is counted once:

- **Inline seen:** supported success and no supported refusal.
- **Mixed:** both success and refusal, possibly in different callers, tiers or JVMs.
- **Refused only:** supported refusals, with no supported success in this dataset.
- **Insufficient evidence:** no supported success/refusal can be resolved.

Size and profile messages are known refusals, not insufficient evidence. Raw
message repetition does not inflate per-context/compilation outcome counts. Both
outcomes in the same unit remain mixed; the analyzer does not guess final machine
code. Unknown observations do not negate a supported success.

The JIT supplies individual attempt decisions and compilation completion, not a
lifetime-final "never inline" signal. The default actionable list contains specific
call contexts labelled **Persistently refused in this workload**: repeated completed,
installed C2 refusals in one worker, matching root, caller, BCI and full inline chain.
A success at that recorded site in any selected worker/tier suppresses promotion;
refusals are never pooled across workers to reach the threshold. Other callers may
inline the same method, so a globally mixed method may still have a qualified site.
Single policy refusals do not bypass the threshold.

Method-wide recurrence, including unresolved virtual dispatch, remains under
expandable diagnostic details and in the refused-only total. Counts are recurrence,
not independent proofs or performance impact. No finding establishes that a method
will never inline. Percentages are of observed signatures,
not test coverage or runtime frequency. Unobserved methods are outside the denominator.

Aggregation is a descriptive union. Different workloads, revisions, class loaders
and JVM configurations are not assumed equivalent. Full context records keep
worker identity, compiler/tier, root, ancestry, caller BCI and callee descriptor.
The stricter context-level persistence classification conservatively excludes
late-inline/intrinsic tasks. Exact source lines, transformed advice provenance,
class-loader disambiguation, JaCoCo joins and cross-revision matching remain outside
this PoC. Byte Buddy advice copying is distinct from JIT inlining.

## CI

Set the pipeline variable `INLINE_OF_DUTY=true` to enable capture in normal HotSpot
test jobs. The common test template skips OpenJ9 variants. Reports and raw captures
are published with `when: always`, alongside existing JUnit results and reports.

### Pipeline report

The `aggregate_inline_of_duty` job runs in `test-summary`, after regular amd64 and
arm64 test stages. Its `when: always` rule includes pipelines with failed test
shards. It uses GitLab's default prior-stage artifact download, preserving the
stage barrier without a hard-coded list of matrix job variants. See GitLab's
[artifact download rules](https://docs.gitlab.com/ci/jobs/job_artifacts/#fetching-artifacts).

The job selects only the current pipeline's namespaced captures and runs the
existing JVM-native `inlineOfDutyReport` task. It recomputes the dataset union by
method signature; it does not add per-job percentages. A success in one job and a
refusal in another is mixed. Copied logs remain deduplicated.

Open **JIT Inlining Report (pipeline)** in the job/MR artifacts. The consolidated
files live in `inline-of-duty-pipeline/` (`index.html`, `details.html`, and
`findings.json`). Raw-log links point to the original producing job artifacts, so
the aggregate job does not publish another copy of all worker logs. The report
shows the pipeline ID and number of captured test jobs. `summary.md` contains only
cumulative counts/percentages, a qualified-context count, and interpretation caveats;
the CI job adds the aggregate artifact URL and prints it in the job log. The `post_jit_inlining_comment` job downloads only the aggregate artifacts and posts
one marker-based PR comment (`<!-- inline-of-duty -->`), updating it on later runs.
It uses the existing `self.gitlab.benchmarks.post-pr-comment` policy to obtain a
short-lived GitHub token and revokes it on exit. No new secret or permission is needed.

Posting runs when `INLINE_OF_DUTY=true` on a PR branch, including after failed test
or report jobs when a summary is available. Default/release branches and merge
queues are excluded. Exactly one open PR must match the branch and tested head SHA;
closed/advanced PRs and superseded pipelines are skipped. Updates are serialized per
ref. GitHub publication failures appear as a warning job without changing test
outcomes. Retry the comment job after retrying aggregation. No live comment is
posted by local plugin checks.

The report includes **available captures**, not an assertion that every scheduled
job produced evidence. Infrastructure failures before capture and untriggered
manual jobs may provide none. If no worker logs are available, the job retains a
zero-worker report and fails with a clear message. Malformed logs and missing logs
for recorded tasks also fail reporting after writing diagnostics. Other test
failures keep their normal CI status. Retry the aggregate job after running or
retrying test shards later in the pipeline.

The CI task uses `-PinlineOfDutyPipelineReport` with the standard GitLab pipeline
ID/project URL to resolve original artifact links. This mode requires the CI
capture layout; ordinary local reports keep relative links.

Raw logs include JVM arguments/properties and may contain application configuration;
use the same access controls as test logs. Manifests record agent jar paths without
agent options.

## Plugin checks

```sh
./gradlew -p build-logic :inline-of-duty:test :inline-of-duty:spotlessCheck
```

The CI comment publisher has offline checks using fake `gh` and `dd-octo-sts`
executables (no external writes or real credentials):

```sh
bash .gitlab/tests/post_jit_inlining_comment_test.sh
```

Analyzer fixtures check evidence and aggregation boundaries. TestKit exercises
normal workers, existing agents, test failures, multiple projects, skipped tests,
configuration-cache reuse, and analysis without rerunning tests.
