# Agent instruction validation

The [LLM Validation Platform](https://github.com/ddoghq/llm-validation-platform)
checks how repository instruction changes affect coding-agent answers. It runs the same
task with the PR's instructions and the target branch's instructions against the
same code, then compares the answers.

[config.yaml](config.yaml) monitors only the root `AGENTS.md`.
[suites/](suites/) contains one practical task: selecting focused Netty instrumentation
tests for normal and latest dependencies. It runs once per instruction version, with
advisory quality results. This checks the integration, not overall instruction
quality. Other guidance files and setup mode are outside this initial scope.

## CI

- `llm validation` skips `master` and pipelines where `AGENTS.md` is unchanged.
- A quality warning does not block. Configuration errors and incomplete evaluations
  after detecting instruction changes fail the job. Earlier infrastructure failures
  may pass CI, but are reported as inconclusive.
- Job artifacts contain `report.md` (summary), `details.json` (answers and judgments),
  `results.json` (scores), and `telemetry.json` (outcome delivery). A summary may also
  appear on the PR.

Both platform references in `.gitlab-ci.yml` pin v0.1.6; update them together.
`LLMVAL_CLAUDE_VERSION` pins the coding-agent CLI separately.
See the [CI guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/ci/README.md)
for credentials, overrides, and failure handling.

## Local offline check

Use Docker from the repository root. No host .NET SDK or model credentials are
needed; downloading the runner requires access to the internal registry.

```shell
mkdir -p build/llm-validation
llmval_git_dir=$(git rev-parse --path-format=absolute --git-common-dir)
docker run --rm --network none \
  -v "$PWD:/repo" -v "$llmval_git_dir:$llmval_git_dir" \
  registry.ddbuild.io/ci/llm-validation-platform/llmval:latest \
  --repo /repo --base-sha HEAD --level minimum --fake \
  --out /repo/build/llm-validation/results.json \
  --report /repo/build/llm-validation/report.md \
  --details /repo/build/llm-validation/details.json
```

The extra Git mount supports worktrees. Reports go to `build/llm-validation/`,
which Git ignores. Fake agents and judges check configuration and report generation;
they do not assess answer quality. The local `latest` image can differ from CI's
pinned release. For live evaluation, follow the platform's
[local runner guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/README.md#run-locally).
