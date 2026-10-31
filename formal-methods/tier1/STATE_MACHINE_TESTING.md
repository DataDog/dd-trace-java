# Deterministic state-machine testing

The writer state-machine harness complements the TLA+ models by executing
generated command sequences against the Java implementation. A small reference
model records every trace accepted before a flush. The implementation must have
serialized the same set when the flush is acknowledged.

`TraceProcessingWorkerStateMachineTest` generates publish prefixes in increasing
length and lexicographic order. For each prefix it deliberately queues the flush
marker before starting the serializer. This removes timing from the schedule and
makes the first mismatch the shortest reproducible counterexample.

The current minimal counterexample is:

```text
PUBLISH_SECONDARY -> FLUSH
```

The secondary trace is accepted first, but the flush marker is placed in the
primary queue. The serializer consumes and acknowledges the primary marker
before visiting the secondary queue, so the flush barrier does not cover all
previously accepted work.

Future state machines should preserve three properties:

1. generated schedules are deterministic and bounded;
2. the oracle is an independent reference model, not internal queue state;
3. failures report a replayable, minimal command sequence.
