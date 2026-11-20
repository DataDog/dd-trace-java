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

## Scheduled setup evaluation

`llm validation (setup smoke)` compares the declared repository setup against minimal guidance
at the same commit. Configure a GitLab pipeline schedule to run every two weeks with
`LLMVAL_SETUP_ENABLED=true` and `LLMVAL_SETUP_PRESET=setup-full`. The `setup-full` preset runs every
setup-eligible case three times.

Setup artifacts are retained for 13 months. A result applies to the evaluated commit and tasks,
not to every individual skill.

The initial cases use read-only answers and fixtures. They do not measure full implementation
workflows, multi-agent reviews, external MCPs/plugins, or alternative model/effort settings.

## Where to find results

Open the CI job's artifacts:

| Check | Report | Answers and judgments |
|---|---|---|
| PR instructions | `report.md` | `details.json` |
| Scheduled setup | `setup-report.md` | `setup-details.json` |
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
