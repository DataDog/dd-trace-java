# Repo context — dd-trace-java

Read only by the orchestrator (Step 0 of `SKILL.md`), not by individual reviewers. Repo-specific; not part of the shared core. This whole `.agents/dd-apm-sdk-review-overrides/` folder is owned by this repo — edit it freely, unlike `.agents/skills/dd-apm-sdk-review/`, which is a verbatim copy of the shared core.

## Related skills in this repo

The other skills in this repo author or review specific things; this one is the general multi-perspective push gate. Cite them as authoritative for their own area, do not invoke them, and note they must not invoke this skill either:

- `techdebt` — duplication / unnecessary complexity / dead-code review, run before marking a PR ready.
- `review-groovy-migration`, `migrate-groovy-to-java` — Groovy→Java test migration tooling and its review pass.
- `apm-integrations` — instrumentation authoring.
- `migrate-junit-source-to-tabletest` — test-source migration tooling.
