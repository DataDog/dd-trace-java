# Performance

Allocations and CPU cost on hot paths (span creation and finish, tagging, propagation, instrumentation callbacks), at startup, and in tooling.

This lens does not keep its own rubric. The tracer performance rubric is owned by the [perf-review skill](../../perf-review/SKILL.md) and its [references](../../perf-review/references/); read and apply those instead of restating them here. Add a rule below only for a repo-specific performance concern that `perf-review` does not already cover.

**When planning:** take the `perf-review` rubric into account when designing the change.

**When reviewing:** apply the `perf-review` rubric to the frozen diff, including its "assume hot" posture and its noise limits. Report a finding when the diff matches a rubric check on a hot, startup, or high-volume path. Cite the rubric check ID (or rule ID, if a rule is added below) and say which path it is on. Report in this skill's severity scale, not `perf-review`'s own format.
