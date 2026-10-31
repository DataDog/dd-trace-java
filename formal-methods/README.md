# Formal methods

This directory contains small executable specifications for lifecycle and
concurrency contracts in the tracer. Models intentionally describe one
handoff or protocol at a time rather than attempting to represent the whole
agent.

## Run the Tier-1 suite

TLC requires Java 11 or newer. Set `TLA2TOOLS_JAR` to a TLA+ tools jar, or put
version 1.7.4 at `/tmp/tla2tools-1.7.4.jar`, then run:

```shell
formal-methods/run-models.sh
```

The runner reads `formal-methods/models.tsv`. A model can be expected to pass
or to produce a specific, documented invariant violation. Expected violations
are diagnostic specifications for confirmed bugs and do not make the runner
fail. An unexpected pass, unexpected violation, parse failure, or wrong
invariant makes the runner return non-zero.

See `formal-methods/tier1/CONTRACTS.md` for the coverage map and the Java code
represented by each model.
