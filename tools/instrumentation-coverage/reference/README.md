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

The RxJava harness and authored catalog now live on andrea.marziali/rxjava3-coverage in
dd-java-agent/instrumentation/rxjava/rxjava-3.0/coverage/reference/.
Generated recordings and reports are owned by that worktree. The ignored build link here exists
only to preserve older artifact paths.
