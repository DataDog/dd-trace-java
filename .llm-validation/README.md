# Java setup compatibility smoke

Temporary experiment for [LLM platform PR #48](https://github.com/ddoghq/llm-validation-platform/pull/48).
This is one plan-only case with one initial run, not a calibrated Java benchmark.

The inventory declares `.claude/skills`, including its existing symlinks, and all shared
`.agents/skills` content. Platform commit `1f97e819f5f93a2d587d16a09ecf37b7776665ea`
supports these repository-internal links: the candidate preserves them, while the baseline
removes both the declared links and their target files. Existing guidance remains unchanged.
The setup job should now progress beyond inventory validation and exercise the case live.
A bootstrap or authentication failure is inconclusive, not a quality verdict.

Both validation jobs are enabled only on `nacho/llm-setup-symlink-smoke`.
Setup is advisory (`allow_failure: true`). The instruction-change job reuses the same case,
triggered by a temporary comment in `AGENTS.md`; it tests CI wiring, not a meaningful guidance change.
Keep this PR in draft and remove the experimental enablement before considering merge.

Local inventory check, from a checkout of the pinned platform:

```shell
dotnet run --project src/Datadog.LlmValidation.Cli -- run --repo <java-checkout> --comparison setup --dry-run --level java-setup-smoke
```

Dry-run does not exercise native links or agents. Trial execution needs a host with native
symlink support. Passing one case does not establish setup benefit or production readiness.
