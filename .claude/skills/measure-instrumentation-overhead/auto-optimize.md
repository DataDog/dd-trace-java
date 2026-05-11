# Auto-optimize mode

Reference for the opt-in workflow triggered by `--auto-optimize` on the skill invocation, or by the user accepting the post-report Y/N offer at SKILL.md step 12.

Auto-mode reads `<work-dir>/suggestions.json` produced by the standard run, applies each integration-scoped suggestion in isolation, decides keep/reject against a multi-signal threshold, then verifies the combined "kept" set in one final run. Output: `<work-dir>/auto/report.md` and uncommitted edits for the user to review with `git diff` and stash or commit.

## Keep criterion

A suggestion is **KEPT** if both:

- **Signal A (wall-clock regression guard)**: variant median per-call ns ≤ `INSTR_REF_NS + max(100, 0.03 * INSTR_REF_NS)`. The variant must not regress wall-clock by more than 3%. This is a guard against actively making things worse; it is NOT a positive signal.
- **Signal B (target-frame JFR event drop)**: at least one frame declared in `s.target_frames` (allocation or CPU) shows a median event count drop of ≥ 30% in the variant JFRs vs the reference JFRs. This is the **primary signal** — it asks "did the change actually reduce events at the frame it claimed?"

**REJECTED** otherwise, with one of these reasons:
- `regressed > 3%` — Signal A failed
- `no effect on target frame (max drop X%)` — Signal A passed but Signal B failed
- `target frame too rare in reference (< 5 events)` — pre-flight rejection
- `patch did not apply` — `Edit` tool couldn't apply the find/replace
- `compile/spotless/shadowJar failed` — build broke after the edit

Why event-drop, not wall-clock-improvement: on noisy workloads (e.g. Postgres-via-TestContainers, ~2% intrinsic noise on 465 µs/call), wall-clock can't distinguish a 0.04-0.08% per-call saving from noise even when the change demonstrably eliminates an allocation site. JFR event counts at a targeted frame are deterministic at allocation-pressure points and detect "the change worked" cleanly.

## Procedure

### 14. Set up auto-mode

- Create `<work-dir>/auto/`.
- Load `<work-dir>/suggestions.json`. Separate into:
  - **Eligible**: `scope == "integration"` AND `find != null` AND `target_frames` non-empty.
  - **Skipped — out of integration scope**: `scope == "core"`.
  - **Skipped — no automatic patch**: `find == null`.
  - **Skipped — no target frame declared**: legacy entries missing `target_frames`.
- **Establish the reference**: re-run the workload's instrumented JVM 3 times with the original agent jar (no changes applied). Capture per-call ns from each run. Median = `INSTR_REF_NS`. Keep all 3 JFRs as `<work-dir>/auto/ref_r{1..3}.jfr` — needed for per-frame event-count medians, not just one.
- **Pre-flight**: for each eligible suggestion `s` and each frame `f` in its `target_frames`, count events whose stack contains `f` in each of the 3 ref JFRs. Take per-frame median: `ref_counts[s.id][f]`. If ALL frames have median `< 5` events, pre-reject `s` with reason "target frame too rare in reference (< 5 events); recommend `--measured 100000` (10×)". Pre-rejected suggestions are recorded but not measured — saves a full build + 3 JVM runs each.
- Reuse the baseline JFR from the standard run.

### 15. Phase A — isolation loop

For each non-pre-rejected eligible suggestion `s`:

1. **Capture original**: `Read` `s.file`, store the full content under `originals[s.file]`.
2. **Apply**: `Edit` with `old_string=s.find`, `new_string=s.replace`. If `Edit` errors → REJECTED `"patch did not apply"`, no revert needed.
3. **Validate**: `./gradlew :<module>:compileJava :<module>:spotlessApply :dd-java-agent:shadowJar`. Any failure → REJECTED `"compile/spotless/shadowJar failed"`, capture error verbatim, revert.
4. **Measure 3 runs**: run the workload JVM 3 times with the new agent jar. Capture per-call ns from stdout, JFRs as `<work-dir>/auto/s<id>_r{1..3}.jfr`.
5. **Compute signals**:
   - `S_NS = median(per_call_ns)`. Signal A passes if `S_NS ≤ INSTR_REF_NS + max(100, 0.03 * INSTR_REF_NS)`.
   - For each frame `f` in `s.target_frames.allocation ∪ s.target_frames.cpu`: count variant events containing `f`, take median `var_counts[s.id][f]`, compute `drop[f] = (ref_counts[s.id][f] - var_counts[s.id][f]) / ref_counts[s.id][f] * 100`. Signal B passes if `max(drop[f]) ≥ 30%`.
6. **Adaptive sampling**: if `max(drop)` is in `[20%, 40%]` (borderline), run 2 more measurements (5 total), recompute medians. If still in `[20%, 40%]`, 2 more (7 total). Cap at 7. The final medians are used for the decision.
7. **Decide** per the keep criterion above.
8. **Revert**: `Write` `originals[s.file]` back to `s.file`. **Always full-content `Write`**, never partial `Edit`-revert — see "Revert mechanism" below. Rebuild shadowJar.
9. If KEPT, remember the `(file, find, replace)` tuple + the kept target-frame metrics for Phase B.

### 16. Phase B — combined run

Only if ≥ 1 suggestion was KEPT in Phase A.

1. Re-apply all KEPT changes by `Edit`. If any `Edit` fails (overlapping changes), record the conflict and degrade the unapplied one to "kept-but-skipped-in-combined".
2. Validate: `compileJava` + `spotlessApply` + `shadowJar`. If any fails, abort Phase B, revert all KEPT changes, record "combined build failed" in the report.
3. Measure 3 runs: capture median `COMBINED_NS`, JFRs `<work-dir>/auto/combined_r{1..3}.jfr`.
4. **Interaction-effect check**: expected `INSTR_REF_NS - sum(per-kept delta_ns)`; actual `COMBINED_NS`. If `|actual - expected| > 0.05 * (INSTR_REF_NS - COMBINED_NS)`, flag in the report (positive = saved more than the sum, negative = overlapping work).
5. **Leave the KEPT edits in the working tree.** Do NOT revert. The user reviews via `git diff`.

### 17. Consolidation report at `<work-dir>/auto/report.md`

- **Preamble**: include the auto-mode limitations (below).
- **Headline**: `INSTR_REF_NS`, `COMBINED_NS` if Phase B ran, cumulative Δ wall-clock (absolute ns + %). Note that this is informational — JFR event drops drive the decisions.
- **Per-suggestion table**: `id | file:line | target frame | ref events | variant median events | drop % | wall-clock Δ | runs | decision | reason`. One row per suggestion; if multiple target frames declared, show the one with the largest drop. `runs` shows 3, 5, or 7 depending on whether adaptive sampling triggered.
- **Skipped sections**: out-of-integration-scope, no-automatic-patch, no-target-frame-declared, target-frame-too-rare (each its own subsection if non-empty).
- **Build/validation failures**: if any variant failed at compile/spotless/shadowJar, list file + captured error.
- **Interaction-effect note** if Phase B flagged one.
- **Working-tree diff**: append `git --no-pager diff` for files touched by KEPT changes — report is self-contained for code review.

### 18. Next-action prompt in chat (not in the report)

- One-line summary: `"Auto-optimize: K kept, R rejected, S skipped. Cumulative Δ: X ns (Y%)."`
- Path to `<work-dir>/auto/report.md`.
- Brief commit-flow reminder: `git diff` to review, `git restore <file>` to drop a specific change, `git add -p` for selective staging, `git stash push -- <file>` to set aside. **Do not commit on the user's behalf.**

## Revert mechanism

Reverts must use **full-content `Write` of the captured original**, never a partial `Edit`-revert of just the body change. Why: `spotlessApply` can remove unused imports as a side-effect of the variant edit (e.g., if the variant removed the last usage of a static import, spotless deletes the import). A body-only `Edit`-revert leaves the file referencing a missing import → compile error on the next variant or in Phase B.

The revert pattern: `Read` the file before each `Edit`, store the full content in memory, `Write` it back to revert. Do NOT use `git checkout` — the working tree has unrelated uncommitted work (skill files, other in-progress changes) that must be preserved.

If a revert fails (file changed externally between capture and restore — rare), auto-mode aborts with a clear error listing what's still applied.

## Auto-mode limitations (state in the report preamble)

- **Primary signal is JFR event drop, not wall-clock.** Auto-mode keeps a suggestion when its declared target frame's event count drops by ≥ 30%; wall-clock is a regression guard only. This detects "the change avoided the allocation it targeted" rather than "wall-clock got faster by an amount we can statistically distinguish".
- **The 30% drop threshold is a heuristic, not a statistical bound.** False positives are possible at low event counts (5-7 in reference). Pre-flight rejects suggestions whose target frame has < 5 events in reference.
- **If a suggestion targets a frame the JFR can't observe** (below allocation sampling resolution), auto-mode can't validate it. Escape hatch: bump `--measured 100000` (10×) so more events cross the sampler.
- **3-7 runs per variant is a low sample size.** Adaptive sampling escalates only when borderline. Real noise estimation needs 10+ runs.
- **JIT warmup repeats per JVM.** Each variant starts a fresh JVM. The workload's warmup phase mitigates but doesn't fully eliminate JIT-induced variance.
- **Integration-scoped only.** Agent-core suggestions are listed for manual review, not applied.
- **One suggestion per file:line.** If a hot path has competing fix proposals, the skill picks one (ranked by ROI) and auto-mode tries only that one.
- **"KEPT" proves the change reduced the targeted events.** It does NOT prove the change is wall-clock faster at production scale — that depends on the proportion of total cost that frame represents. Trust the JFR signal for "the change worked"; trust nothing about wall-clock improvement at the scale of this synthetic workload.

## Example (predicted outcome on the JDBC-DBM run with v2)

| id | file:line | target frame | ref events | variant events | drop | wall-clock Δ | decision |
|---:|---|---|---:|---:|---:|---:|---|
| 1 | `SQLCommenter.java:42` | `SQLCommenter.inject` | 5 | ~1 | −80% | +0.4% | KEPT |
| 3 | `JDBCDecorator.java:387` | `JDBCDecorator.setApplicationName` | 32 | 31 | −3% | −0.6% | REJECTED (no effect on target frame) |

Suggestion 1 did exactly what was claimed (inject allocations dropped from 5 to ~1) even though wall-clock barely moved. Suggestion 3 (drop `INSTRUMENTATION_TIME_MS` self-timing) didn't reduce events at the targeted frame because the dominant cost is `setClientInfo`, not the timing tag — auto-mode surfaces that honestly.
