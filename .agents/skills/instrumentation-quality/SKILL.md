---
name: instrumentation-quality
description: Build library knowledge, collect instrumentation-test evidence, assess assertions, and improve tests with Pharos. Use for the complete instrumentation-quality loop or resuming an investigation; use library-flow-knowledge for KB authoring alone.
---

# Instrumentation quality — Pharos

Start with the user's module and intended scope. Reuse existing artifacts and continue the requested
work without making the user invoke each phase. Scripts resolve, collect, validate and render;
the LLM interprets library behavior, assesses assertions and implements justified test changes.
No external LLM API, universal quality threshold, or acceptance-policy engine is required.

The working sequence is **discover → KB → collect → assess → improve → collect → reassess**.
The report is an output and an investigation aid, not a prerequisite UI interaction.
Read [the command and artifact guide](../../../tools/instrumentation-coverage/WORKFLOW.md) for the
phase you need. Run commands from the repository root; script names below live in
`tools/instrumentation-coverage/` and are invoked with `python3` plus that path.

## Find the starting point

Inspect the module build, test harness, `coverage/` knowledge, and any supplied report or session.
Infer artifact coordinates, version, adapter, test tasks and observation scope from local evidence.
Ask only for information that cannot be established locally and affects the requested work.

- No knowledge: initialize configuration and generate the graph, then author the KB.
- Changed library/dependency identity: regenerate the graph and revisit affected knowledge.
- Existing knowledge, no current run: reconcile its scope and inputs, then collect.
- Current run, unassessed assertions: prepare a dossier and assess.
- Report finding or downloaded task: inspect its evidence and existing tests, then investigate.
- Session supplied: `pharos.py resume --session PATH` prints the saved handoff. Read the indicated
  dossier/assessment; do not reconstruct paths from chat history or choose an arbitrary latest run.

## Reconcile catalog scope before reuse

A matching dependency version and valid anchors do not make an existing catalog current. Inventory
upstream test sources independently of selected flows, then reconcile prior catalog revisions and
supplied known findings. Use [the reconciliation procedure](../../../tools/instrumentation-coverage/CATALOG_RECONCILIATION.md).
Missing inputs require inventory/review before collection; unresolved signals may remain explicit
while collection proceeds. Never silently drop a prior scenario or treat a known reproducer as
covered by a generic flow: preserve its preconditions and assertion obligations in the mapping.

Keep unreviewed families as candidates, not arbitrary exclusions. Explain exclusions and flow
replacements with source evidence. Do not update input hashes merely to silence validation. Check
available prior artifacts identified by the task or module history; a private queue is not a CI
dependency. Record supplied findings in portable inputs so another worktree retains them.

The headline shows verified behaviors / declared mapped behaviors as a percentage. Candidate
families stay outside the denominator. Catalog review status must not hide the percentage or add
an omissions panel to the report. Keep inventory bookkeeping in authoring artifacts. Context
presence, passing tests and shared methods cannot establish behavioral completeness.

## Build or update the KB

Use [library-flow-knowledge](../library-flow-knowledge/SKILL.md). Its inputs are version-matched
library source, upstream tests, documentation and graph queries. The KB belongs to the module;
particular local test identities and run outcomes do not.

Run `workflow.py validate-knowledge --module MODULE` after authoring. Resolve structural failures;
preserve semantic uncertainty. Draft status describes evidence, not a requirement to pause the
user's already-authorized investigation. Never label an LLM draft as human-reviewed.

## Collect and assess

Run `workflow.py run --module MODULE` with the selected test JVM when needed. Inspect test outcomes
and collection health before interpreting gaps. Preserve failed-run diagnostics; don't turn failed
collection into a successful report.

Use `pharos.py prepare --report REPORT --source TEST_SOURCE --source HELPER_SOURCE --output ITERATION`.
Read its dossier, inspect relevant setup/assertions/helpers, and author `assessment.json`. For every
binding explain which assertions support the declared behavior and cite the exact source lines.
Keep unresolved behaviors unassessed; method overlap, test names and passing results alone do not
justify support. Then run `pharos.py assess --session ITERATION/session.json`.

Upstream runtime recording is optional enrichment. The generic assessment path works from an
ordinary module report and local test sources; it does not require the Spring cartography pilot.

## Improve and repeat

Select a concrete unresolved behavior from the report/task data. Inspect existing tests before
adding one. An unobserved reference method is a clue, not a mandatory call. If the observation or
mapping is wrong, correct that before inventing a missing-test claim.

Read [RECOVERY.md](../../../tools/instrumentation-coverage/RECOVERY.md) when choosing an experiment.
Inspect the fixture's actual input/error behavior before asserting a status or trace shape. Add or
strengthen behavioral and relevant instrumentation assertions; don't merely execute methods.
Follow repository testing guidance. Production changes require scope from the user's request;
otherwise retain a reproducer and explain any demonstrated instrumentation failure.

After changing tests, run fresh collection and prepare a **new** iteration with `--previous` pointing
to the prior generic assessment. The dossier shows source changes and old bindings, but transfers
no assertion support automatically. Inspect changed inputs, helpers, assertions and fresh test IDs;
reassess and validate. Reuse the KB unless the behavior, library identity or mapping actually changed.
Do not patch hashes in an old assessment to make it pass.

Continue the user's requested investigation using the new evidence. Report actual changes, test
results, unresolved questions and artifact paths; don't claim autonomous completion of semantic
review or correctness from a percentage. Preserve execution, assertion support, Context observations
and expected identity/lifecycle as distinct evidence.
