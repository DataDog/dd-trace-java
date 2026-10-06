# Instrumentation quality workflow

**Catalog → upstream recording → our-test collection → fingerprint classification → HTML report.**

## 1. Build the catalog

Use .agents/skills/library-flow-knowledge/SKILL.md. Read version-matched documentation, source and
upstream tests to name behaviors and group them into functionality families. Generate/reuse the
matching static graph. Keep library-specific selections in catalog data, not Python generators.

## 2. Record upstream examples

The current reference harness supports RxJava 3.0.0. It records original JUnit tests and separately
identified curated reference examples, with a recorder-off baseline and two recorded repetitions.

```sh
python3 tools/instrumentation-coverage/reference/plan.py --plan CATALOG --output SELECTIONS
python3 tools/instrumentation-coverage/reference/run.py --source UPSTREAM --classes SELECTIONS --harness HARNESS
```

Keep each example/repetition separately. Use recorded entries and the static graph to assign
meaningful stage names. Check outcomes, collection health and artifact identity automatically.
Reuse existing recordings when their inputs have not changed.

## 3. Collect our tests

Use the existing agent-enabled collector:

```sh
python3 tools/instrumentation-coverage/workflow.py run --module MODULE
```

Use --test-jvm when needed. Keep method counts per exact local test, context observations,
collector health and JUnit outcomes. Collector errors are not missing test coverage.

## 4. Classify and render

```sh
python3 tools/instrumentation-coverage/reference/report.py \
  --reference UPSTREAM_RUN --ours LOCAL_RUN --plan CATALOG --output REPORT_DIRECTORY
```

The classifier compares each local test with each upstream example/repetition. It uses inverse
document frequency to downweight methods shared across reference scenarios, logarithmic method
counts to avoid domination by loops, operator/stage fingerprints and the broader library path.
Use observed edges/handoffs only when available in comparable form on both sides; the existing
local collector records method entries, so its comparison does not pretend to have dynamic edges.

The score is 75% operator/stage fingerprint similarity and 25% whole-library fingerprint similarity.
Within the operator, reference recall receives more weight than local precision so a composed
local test can match several upstream behaviors. The strongest actual repetition is selected;
repetitions are never merged into an artificial path.
When both fingerprints contain terminal callbacks but their success/completion/error sets are
disjoint, halve the score. This prevents shared subscription entries hiding opposite outcomes.

Default labels: **Likely match** at score >= 0.80, **Partial match** at score >= 0.50, otherwise
**No match**. These thresholds are configurable in catalog classification. Scores are similarity,
not calibrated probabilities. Show close alternatives within 0.05 of a test's strongest family
match; do not convert ties into a manual-review requirement.

The HTML shows family colors, scenario scores, best matching local tests, per-stage matched/missing
methods, context observations and test outcomes. It is the execution-based quality report.
No prepare/assess phase, human approval, source-cited assertion binding, or "unverified" state
is required. The old assertion-review commands remain available only for explicit requests.

## Iterate

For rendering/scoring changes, replay saved collections. For changed tests, collect again and
classify automatically against the unchanged reference. Production fixes require authorization.
Do not silently remove scenarios or edit failed runs into successes. Retain authorized exclusions
and historical failures in structured data, not banners.

Legacy static KB tools (init, graph, validate-knowledge) remain available. Their schema is in
AUTHORING.md and KNOWLEDGE_FORMAT.md; legacy assertion-supported reports keep their original
meaning and are not silently renamed as fingerprint coverage.
