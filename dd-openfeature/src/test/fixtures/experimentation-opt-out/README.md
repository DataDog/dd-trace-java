# Experimentation opt-out test cases

`cases.json` covers 90 combinations of opt-out mode, subject timestamp, allocation activation,
and exposure logging. `ExperimentationOptOutProviderTest` runs each case through the production
parser and evaluator and checks the selected allocation or evaluation error.

The source is [`browser-proposal.jsonl`](https://github.com/DataDog/ffe-system-test-data/blob/64ea4a312f2167c7d48c7df642ae1e5d0a9327de/contracts/experimentation-opt-out/corpus/browser-proposal.jsonl)
at `DataDog/ffe-system-test-data` revision `64ea4a312f2167c7d48c7df642ae1e5d0a9327de`.
Its SHA-256 is `4c4628ae30217bd2ead2ef34144f43e0163f9916ff0c8accae0847391dff64a6`.

Extraction preserves source order, selects only `kind == "policy"`, removes `kind` and
`decision_ids`, and strips the `policy/` prefix from each ID. No inputs or expected results
are changed. Java's approved adoption scope includes these policy rows; the browser timestamp,
transport, and pending-state rows do not define Java behavior. Native timestamp expectations
live in `ExperimentationOptOutTest`.

To verify both the pinned source bytes and equality of all extracted rows, save the source
file from that revision and run from this directory:

```sh
./verify.sh /path/to/browser-proposal.jsonl
```

The provider Gradle test tasks declare both this directory and the canonical fixture directory
as inputs, so fixture-only changes invalidate their cached results.
