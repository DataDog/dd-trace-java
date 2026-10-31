# Deterministic state-machine testing

The writer state-machine harness complements the TLA+ models by executing
generated command sequences against the Java implementation. A small reference
model records every trace accepted before a flush. The implementation must have
serialized the same set when the flush is acknowledged.

`TraceProcessingWorkerStateMachineTest` generates command sequences in increasing
length and lexicographic order. For each sequence it deliberately queues flush
markers before starting the serializer. This removes timing from the schedule.
It explores the full bound, groups failures by causal signature, and retains the
first (therefore shortest) schedule for each distinct problem.

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
3. failures report a replayable, minimal command sequence without stopping
   exploration after a previously known defect.

The saturation state machine is independent of flush ordering. It fills the
primary and secondary lanes without starting consumers and checks that every
rejected publish is reported as an explicit lane-specific terminal drop.

## Remote-config listener fan-out

`ProductStateListenerStateMachineTest` injects a failure into each listener
phase (`accept`, `remove`, and `commit`) ahead of a healthy listener. It then
replays the same remote-config state. The reference model requires the healthy
listener to observe the complete phase and requires incomplete delivery to
remain retryable.

All three injected phases currently violate the model. In each case the healthy
listener misses the failed phase and replay reports no change, so delivery is
never retried. The `remove` and `commit` failures are distinct additions to the
previously known `accept` failure.

## Telemetry retry ownership

`TelemetryServiceStateMachineTest` generates event additions and successful or
failed sends. It extracts event identifiers from the real serialized request
body and compares them with an independent queued/retry-buffered/delivered/drop
model.

The current minimal counterexample is:

```text
SEND_FAILURE -> ADD -> SEND_SUCCESS
```

The first empty failed request creates an empty retry buffer. The next send
retries an empty heartbeat instead of taking the newly queued event, then
returns `false` even though that event remains queued.
