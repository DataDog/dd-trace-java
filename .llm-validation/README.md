# Java setup compatibility smoke

Temporary experiment for [LLM platform PR #48](https://github.com/ddoghq/llm-validation-platform/pull/48).
This is one plan-only case with one initial run, not a calibrated Java benchmark.

The inventory declares real guidance files and `.agents/skills` contents. Existing
`.claude/skills/*` symlinks remain unchanged. Platform commit
`8c4f8ecd631e9de63730d1c4051569c296b16481` is expected to reject them during
setup inventory validation with exit 2, before Claude installation or gateway authentication.
An INCOMPLETE report is expected; it is not a quality verdict or evidence that Java's guidance is bad.
A fetch, entitlement, image, or toolchain failure would not validate the symlink reproduction.

Both validation jobs are enabled only on `nacho/llm-setup-symlink-smoke`.
Setup is advisory (`allow_failure: true`). The instruction-change job reuses the same case,
triggered by a temporary comment in `AGENTS.md`; it tests CI wiring, not a meaningful guidance change.
Keep this PR in draft and remove the experimental enablement before considering merge.

Local reproduction, from a checkout of the pinned platform:

```shell
dotnet run --project src/Datadog.LlmValidation.Cli -- run --repo <java-checkout> --comparison setup --dry-run --level java-setup-smoke
```

The next step is to test repository-internal symlink support against this same layout,
then run the case live once inventory validation succeeds. Passing one case does not
establish setup benefit or production readiness.
