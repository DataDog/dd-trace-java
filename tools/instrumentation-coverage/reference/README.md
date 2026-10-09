# Upstream execution classification

Reusable Pharos tools live here. Library-specific catalogs, recorder hooks, test dependencies and
reference examples live with their instrumentation workstream, outside this tooling directory.

The recorder runner takes an explicit harness, source checkout and selection file:

```sh
python3 tools/instrumentation-coverage/reference/plan.py --plan CATALOG --output SELECTIONS
python3 tools/instrumentation-coverage/reference/run.py --source UPSTREAM --classes SELECTIONS --harness HARNESS
python3 tools/instrumentation-coverage/reference/report.py \
  --reference UPSTREAM_RUN --ours LOCAL_RUN --plan CATALOG --output REPORT_DIRECTORY
```

The classifier uses execution fingerprints internally. The original Pharos viewer displays
reference methods hit, context state, functionality families, stages and test outcomes.
Manual assertion review is optional and is not a report-generation gate.
See ../WORKFLOW.md for collection and matching details.

Each flow selects a library-relative JVM class using `referenceClass`; `operator` is accepted as a
legacy alias. Method names have no special lifecycle or outcome meaning in the classifier.
Lifecycle stage labels and selectors belong in the catalog (`stageDefinitions` or per-flow `stages`).
Without those definitions, the viewer shows an ungrouped summary and the selected class's recorded
methods. Adding or changing stage selectors changes the reference scope; compare reports only when
their scope is the same.

Generated recordings and reports belong to their instrumentation workstream, not this directory.
