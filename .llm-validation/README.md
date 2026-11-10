# Agent instruction validation

The [LLM Validation Platform](https://github.com/ddoghq/llm-validation-platform) compares agent
answers under baseline and candidate instructions. This directory also reports instruction text
growth and documents how to review guidance for correctness, scope, and placement.

## CI

`llm validation` reports context growth, then evaluates changes to the
`instruction_files` in [config.yaml](config.yaml): `AGENTS.md` and `.claude/CLAUDE.md`.
The [suite](suites/java-agent-smoke.yaml) covers focused validation, Java test conventions,
premain safety, and an unrelated Java task to look for broader quality regressions.

The job skips `master`. Context reporting runs on other eligible pipelines even when unchanged
instructions cause the behavioral evaluation to skip. Growth and quality findings are advisory.
Preflight errors, configuration errors, and incomplete evaluations after detecting instruction
changes fail the job. During calibration, `allow_failure: true` keeps these failures visible without
failing the GitLab pipeline. Keep this job optional in merge checks while testing. Earlier platform
infrastructure failures may pass as inconclusive.

Read the job artifacts:

- `build/llm-validation/context-growth.md`: committed instruction bytes, including relative imports
  counted once. This is a size proxy, not runtime tokens or total context; user settings and
  on-demand skill bodies are excluded. Missing imports or unsupported external imports and symlinks
  produce an incomplete measurement.
- `report.md`: behavioral comparison summary; `details.json`: answers and judgments.
- `results.json`: scores; `telemetry.json`: outcome delivery status.

## Review instruction changes

A PASS does not establish that guidance is necessary or belongs in `AGENTS.md`. Maintainers should check:

- Correctness against maintained documentation and code; cite sources and resolve contradictions.
- Whether a concise root rule or pointer is sufficient, with procedures in `.agents/skills/`,
  module-scoped guidance, or existing documentation.
- Duplication, unnecessary imports, and requirements that impose unrelated work.

Use [CONTRIBUTING.md](../CONTRIBUTING.md) and the relevant [developer guides](../AGENTS.md#key-documentation-read-on-demand-dont-load-upfront).
When adding imports or moving guidance into a skill, list every affected file in `instruction_files`
so both instruction versions are compared. Verify new case criteria against repository sources and
keep unrelated control cases in the PR preset.

## Setup evaluation

The separate `setup` inventory in [config.yaml](config.yaml) declares root/build-module guidance
and both skill directories. Existing `setup.directories` support includes tracked files recursively
and resolves internal aliases; it does not expand instruction-change monitoring.

Setup mode compares the declared guidance against a minimal setup at the same commit. It is not
enabled in CI. With the pinned platform runner, use `--comparison setup --level minimum`;
add `--dry-run` to inspect the inventory and selection without calling a model. It reuses the
existing suite, so conclusions apply only to the tasks covered.

## Local checks

From the repository root, check context growth with Bash, Git, awk, and standard shell utilities.
This compares committed revisions, excluding unstaged edits:

```shell
bash .llm-validation/tests/test_context_growth.sh
git fetch --no-tags origin master
instruction_base=$(git merge-base HEAD origin/master)
bash .llm-validation/context_growth.sh --base-ref "$instruction_base" \
  --report build/llm-validation/context-growth.md
```

For an offline behavior smoke, use Docker; no host .NET SDK or model credentials are needed.
Downloading the image requires internal registry access.

```shell
mkdir -p build/llm-validation
llmval_git_dir=$(git rev-parse --path-format=absolute --git-common-dir)
docker run --rm --network none \
  -v "$PWD:/repo" -v "$llmval_git_dir:$llmval_git_dir" \
  registry.ddbuild.io/ci/llm-validation-platform/llmval:latest \
  --repo /repo --base-sha HEAD --level minimum --runs 1 --fake \
  --out /repo/build/llm-validation/results.json \
  --report /repo/build/llm-validation/report.md \
  --details /repo/build/llm-validation/details.json
```

The extra Git mount supports worktrees. Fake runs check configuration and reporting, not answer quality.
The `latest` image can differ from the pinned CI release. See the platform's
[runner guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/README.md#run-locally)
for live evaluations and [CI guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/ci/README.md)
for configuration. When upgrading, update both platform references in `.gitlab-ci.yml` together
and preserve the shared template's artifact paths. The coding-agent CLI is pinned separately.
