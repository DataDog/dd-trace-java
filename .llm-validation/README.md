# Agent instruction validation

Regular validation compares the PR's `AGENTS.md` with its target-branch version,
using the same source revision for both variants. The shared LLM Validation Platform
owns execution, reporting, authentication, and CI artifacts.

The initial suite reuses the Java pilot's single SpotBugs plan case. It runs once
per variant and is advisory (`only_warn: true`): it checks the integration, not
representative instruction quality or setup benefit. Setup evaluation is disabled.

## CI behavior

The `llm validation` job skips `master` and self-skips other pipelines when
`AGENTS.md` is unchanged. This integration-only change therefore normally skips
live evaluation. Later monitored changes require complete agent and judge results;
infrastructure failures after change detection and configuration errors fail the job.
Early infrastructure failures use the platform's default fail-open behavior.

Read `report.md`, `results.json`, and `details.json` in the job artifacts;
`telemetry.json` records outcome delivery. The platform can also publish a PR summary.

Both the include reference and `LLMVAL_PLATFORM_REF` in `.gitlab-ci.yml` are pinned
to v0.1.6's commit. Update them together. Claude CLI is pinned separately using
`LLMVAL_CLAUDE_VERSION`.

## Local offline check

From a checkout of the pinned platform, with a compatible .NET SDK installed:

```shell
dotnet run --project src/Datadog.LlmValidation.Cli -- run \
  --repo <java-checkout> --base-sha master --fake --level minimum \
  --out <output-directory>/results.json \
  --report <output-directory>/report.md \
  --details <output-directory>/details.json
```

Create the output directory first. Fake execution checks configuration and report
plumbing without calling agents or judges; it does not measure answer quality.
For an explicit live smoke, omit `--fake` and add `--require-complete`, using the
[platform's gateway authentication](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/README.md#run-locally).
See the [CI guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/ci/README.md)
for enforcement, credentials, and overrides. Expand the shared suite before
introducing setup monitoring or blocking quality cases.
