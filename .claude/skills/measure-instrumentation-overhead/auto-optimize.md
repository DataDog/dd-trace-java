# Auto-optimize mode

Triggered by `--auto-optimize` or by accepting the Y/N offer at step 13.

Reads `suggestions.json`, applies each integration-scoped suggestion in isolation, keeps or rejects it using two signals, then verifies all kept changes together. Output: `<work-dir>/auto/report.md` + uncommitted edits.

## Keep criterion

A suggestion is **KEPT** if both signals pass:

- **Signal A** (regression guard): variant median ns ≤ `INSTR_REF_NS + max(100, 0.03 × INSTR_REF_NS)`. Guards against actively making things worse; not a positive signal.
- **Signal B** (primary signal): at least one declared `target_frames` frame shows ≥ 30% fewer JFR events in the variant vs reference.

**REJECTED** reasons: `regressed > 3%` · `no effect on target frame` · `target frame too rare (< 5 events)` · `patch did not apply` · `build failed`

Signal B, not wall-clock, drives the decision. Wall-clock at sub-10 µs per call is too noisy to distinguish small savings from JIT variance.

## Step 14 — Setup

1. Create `<work-dir>/auto/`.
2. Load `suggestions.json`. Classify:
   - **Eligible**: `scope == "integration"` AND `find != null` AND `target_frames` non-empty.
   - **Skipped (core scope)**: `scope == "core"`.
   - **Skipped (no patch)**: `find == null`.
   - **Skipped (no target frame)**: `target_frames` missing.
3. **Reference runs** — run the JMH benchmark 3 times with the original agent jar:
   ```bash
   java -cp "$WORKDIR/classes:$(cat $WORKDIR/classpath.txt):$(cat $WORKDIR/jmh_cp.txt)" \
     org.openjdk.jmh.Main "overhead.Workload" \
     -f 1 -wi "$WI" -w "${T}s" -i "$I" -r "${T}s" -bm avgt -tu ns \
     -jvmArgs "-javaagent:$AGENT_JAR ... -XX:StartFlightRecording=...,filename=$WORKDIR/auto/ref_r${run}.jfr,..." \
     -rf json -rff "$WORKDIR/auto/ref_r${run}.json"
   ```
   Extract ns: `python3 -c "import json; d=json.load(open('ref_r${run}.json')); print(int(d[0]['primaryMetric']['score']))"`

   Median of 3 runs = `INSTR_REF_NS`. Keep all 3 JFRs.

4. **Pre-flight** — for each eligible suggestion, count events at each `target_frames` frame in the 3 ref JFRs. If all frames have median < 5 events: pre-reject with `"target frame too rare"`.
5. Reuse `baseline.jfr` from the standard run.

## Step 15 — Phase A: isolation loop

For each non-pre-rejected eligible suggestion `s`:

1. `Read` `s.file` → store full content in `originals[s.file]`.
2. `Edit` `old_string=s.find`, `new_string=s.replace`. On error → REJECTED `"patch did not apply"`.
3. `./gradlew :<module>:compileJava :<module>:spotlessApply :dd-java-agent:shadowJar`. On failure → REJECTED `"build failed"`, revert.
4. Run JMH benchmark 3 times. Extract ns from each JSON. JFRs → `auto/s<id>_r{1..3}.jfr`.
5. Compute signals:
   - `S_NS = median(ns)`. Signal A: `S_NS ≤ INSTR_REF_NS + max(100, 0.03 × INSTR_REF_NS)`.
   - For each frame `f`: `drop[f] = (ref_count[f] − var_count[f]) / ref_count[f] × 100`. Signal B: `max(drop) ≥ 30%`.
6. **Adaptive sampling**: if `max(drop)` is in [20%, 40%], run 2 more (total 5). If still borderline, 2 more (total 7). Cap at 7.
7. Decide per keep criterion.
8. **Revert**: `Write` `originals[s.file]` back. Always full-content `Write` — never partial `Edit`-revert (spotless can strip imports as a side-effect, breaking subsequent builds). Rebuild shadowJar.
9. If KEPT: store `(file, find, replace)` + kept frame metrics for Phase B.

## Step 16 — Phase B: combined run

Only if ≥ 1 KEPT in Phase A.

1. Re-apply all KEPT changes (`Edit`). Overlapping edits → record conflict, mark as "kept-but-skipped".
2. `compileJava` + `spotlessApply` + `shadowJar`. On failure → abort Phase B, revert all KEPT, record "combined build failed".
3. Run JMH 3 times. Median = `COMBINED_NS`. JFRs → `auto/combined_r{1..3}.jfr`.
4. Interaction check: expected = `INSTR_REF_NS − sum(per-kept Δns)`; actual = `COMBINED_NS`. Flag if `|actual − expected| > 0.05 × (INSTR_REF_NS − COMBINED_NS)`. `COMBINED_NS` above `INSTR_REF_NS` is normal JIT/fork variance — it does not invalidate a KEPT decision.
5. **Leave KEPT edits in the working tree.** User reviews via `git diff`.

## Step 17 — Auto report at `<work-dir>/auto/report.md`

- Preamble: auto-mode limitations (below).
- Headline: `INSTR_REF_NS`, `COMBINED_NS`, cumulative Δ ns + %.
- Per-suggestion table: `id | file:line | target frame | ref events | variant events | drop% | wall-clock Δ | runs | decision | reason`.
- Skipped sections (one subsection each if non-empty): core scope · no patch · no target frame · too rare.
- Build failures: file + error verbatim.
- Interaction-effect note if Phase B flagged one.
- `git --no-pager diff` for files with KEPT changes.

## Step 18 — Chat summary

- `"Auto-optimize: K kept, R rejected, S skipped. Cumulative Δ: X ns (Y%)."`
- Path to `auto/report.md`.
- `git diff` to review · `git restore <file>` to drop · `git add -p` to stage selectively.

## Revert mechanism

Always `Write` the full captured original — never a partial `Edit`-revert. Spotless can delete imports as a side-effect of variant edits; a body-only revert leaves the file broken. Do not use `git checkout` (preserves unrelated uncommitted work).

## Auto-mode limitations

- Primary signal is JFR event drop ≥ 30%, not wall-clock improvement.
- The 30% threshold is a heuristic. False positives possible at low event counts (5–7).
- 3–7 JMH runs per variant is a small sample size.
- Integration-scoped only. Agent-core suggestions are listed for manual review.
- "KEPT" means the change reduced events at the targeted frame, not that it improves production throughput.

## Example

| id | file:line | target frame | ref | variant | drop | wall-clock Δ | decision |
|---:|---|---|---:|---:|---:|---:|---|
| 1 | `SQLCommenter.java:42` | `SQLCommenter.inject` | 5 | ~1 | −80% | +0.4% | KEPT |
| 3 | `JDBCDecorator.java:387` | `JDBCDecorator.setApplicationName` | 32 | 31 | −3% | −0.6% | REJECTED |
