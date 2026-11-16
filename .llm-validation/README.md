# Agent instruction validation

The [LLM Validation Platform](https://github.com/ddoghq/llm-validation-platform) checks whether
repository guidance improves agent answers. Both CI jobs are advisory and do not block merging.

## PR checks

`llm validation` compares answers before and after changes to `AGENTS.md` and `.claude/CLAUDE.md`.
It skips `master` and skips answer evaluation when those files are unchanged. It also reports
instruction size, including relative imports, in bytes rather than runtime tokens.

When reviewing guidance, check correctness, duplication, and whether detailed procedures belong
in a skill or scoped documentation. Read the answers alongside the scores; a PASS does not prove
every instruction is useful.

## Monthly setup evaluation

`llm validation (setup smoke)` compares the declared repository setup against minimal guidance
at the same commit. The `setup-full` preset runs every setup-eligible case three times.

After merging, create a schedule in
[Build > Pipeline schedules](https://gitlab.ddbuild.io/DataDog/apm-reliability/dd-trace-java/-/pipeline_schedules):

| Setting | Value |
|---|---|
| Target branch | `master` |
| Cron | `0 21 1 * *` — 9 p.m. on the 1st of each month |
| Time zone | `America/New_York` |
| Variable | `LLMVAL_SETUP_ENABLED=true` |
| Variable | `LLMVAL_SETUP_PRESET=setup-full` |

Leave case/run overrides and `POPULATE_CACHE` unset. The same variables enable an on-demand
full run. Other eligible Java CI jobs may run in the scheduled pipeline too.

Review the monthly report and representative answers, including the unrelated Java controls.
Confirm the evaluation is complete, live, and covers the full suite; retry incomplete runs.
Record the report link, evaluated commit, and findings on
[APMLP-2032](https://datadoghq.atlassian.net/browse/APMLP-2032). Setup artifacts are retained for
13 months. A result applies to the evaluated commit and tasks, not to every individual skill.

The initial cases use read-only answers and fixtures. They do not measure full implementation
workflows, multi-agent reviews, external MCPs/plugins, or alternative model/effort settings.

## Where to find results

Open the CI job's artifacts:

| Check | Report | Answers and judgments |
|---|---|---|
| PR instructions | `report.md` | `details.json` |
| Monthly setup | `setup-report.md` | `setup-details.json` |
| Instruction size | `build/llm-validation/context-growth.md` | — |

## Maintaining the checks

- Update [config.yaml](config.yaml): `instruction_files` controls PR comparisons; `setup` lists
  guidance removed from the minimal variant. Include new guidance and required resources.
- Add cases to [the suite](suites/java-agent-instructions.yaml), verifying expected behavior
  against repository code and documentation. Add PR cases to the `minimum` preset; use
  `comparisons: [setup]` for setup-only cases. `setup-full` automatically includes eligible cases.
- Keep both platform references in `.gitlab-ci.yml` in sync when upgrading.

For local execution and troubleshooting, see the pinned platform's
[runner guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/README.md#run-locally)
and [CI guide](https://github.com/ddoghq/llm-validation-platform/blob/v0.1.6/ci/README.md).
