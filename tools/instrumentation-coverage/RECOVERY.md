# Evidence-guided recovery

Use a report finding to choose the next experiment. Recovery is iterative: change one test or
observation boundary, collect a new run, and compare it with the baseline before considering a
production change.

| Finding | Meaning | First action | Evidence required before production changes |
| --- | --- | --- | --- |
| No matching test | No attributed scenario satisfies the reviewed flow predicate | Inspect existing test assertions and fixture behavior; add a focused test only if needed | The test executes the required anchors and asserts the intended behavior |
| Outside entry inventory | The graph contains the method but the collector did not observe it | Add the defining class to `observation.json`, accounting for optional dependencies | A fresh healthy run classifies its actual Context entries |
| Eligible but unobserved | The method was observable but absent in the selected scenario | Check fixture construction and the static candidate route | A focused run demonstrates whether the expected branch is reachable |
| Root or mixed Context | Some entries had root Context; this may be intentional or phase-specific | Add an explicit Context expectation at the relevant callback or lifecycle point | The same scenario demonstrates the expected Context and isolates the failing boundary |
| Failed Context expectation | Behavior executes but expected Context is absent or wrong | Inspect existing instrumentation and the nearest handoff | A minimal change makes the focused test pass without leakage or trace regressions |
| Stale knowledge | Library identity, bindings, or documented behavior changed | Regenerate the graph and refresh affected claims | Version-matched sources bind and changed semantics are reviewed |

## Compare runs

```sh
python3 tools/instrumentation-coverage/workflow.py compare \
  --baseline /path/to/baseline/report/report.json \
  --candidate /path/to/candidate/report/report.json \
  --output /tmp/instrumentation-coverage-comparison.json
```

The comparison is valid only for the same library identity and version. It reports changed flow
coverage and matching-test counts. Inspect scenario-level evidence for causal questions; aggregate
method totals do not prove that one request crossed a boundary.

## Completion criteria

A missing-test recovery is complete when the new scenario has behavioral assertions, satisfies its
reviewed flow predicate, produces healthy attributed observations, and leaves existing tests green.

An instrumentation recovery additionally requires a demonstrated pre-change failure, a passing
post-change run for the same scenario, request isolation or leak checks where relevant, and the
module's compatibility checks. Keep intentional root execution documented rather than forcing it
green.

After fresh collection, use `pharos.py prepare --previous PRIOR_ASSESSMENT` and `pharos.py assess`
to rebind assertion evidence. See [WORKFLOW.md](WORKFLOW.md); never refresh hashes in the old
assessment as a substitute for reassessment.
