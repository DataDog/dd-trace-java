# Long-running trace ownership model

`TrackerOwnership.tla` models the ownership hand-off between
`PendingTraceBuffer.Worker` and `LongRunningTracesTracker`.

The safety invariant is:

> Every live pending trace is owned by the queue, the worker, the tracker, or
> the buffer fallback path.

The current implementation violates this invariant when the tracker is at
capacity:

1. the worker removes the trace from the queue;
2. `LongRunningTracesTracker.add` changes its state from `TO_TRACK` to
   `TRACKED`;
3. `addTrace` observes a full tracker and does not retain the trace;
4. `add` still returns `true`, so the worker skips the fallback path and
   relinquishes the trace.

Run the model from the repository root:

```shell
mkdir -p /tmp/dd-trace-java-tlc/tracker-ownership
java -XX:+UseParallelGC -jar /tmp/tla2tools-1.7.4.jar \
  -config formal-methods/long-running-traces/TrackerOwnership.cfg \
  -metadir /tmp/dd-trace-java-tlc/tracker-ownership \
  formal-methods/long-running-traces/TrackerOwnership.tla
```

TLC is expected to report `Invariant LiveTraceHasOwner is violated` with a
four-state counterexample.

The corresponding Java regression is
`LongRunningTracesTrackerTest.fullTrackerLeavesOverflowTraceForBufferFallback`.
On the unfixed implementation it fails because `add` returns `true` and leaves
the rejected trace in `TRACKED` state.

The practical impact is that, under long-running tracker saturation, finished
spans belonging to a trace with an unfinished span or continuation can remain
unreported until that outstanding work finishes. If it never finishes, the
trace no longer has any component capable of writing it.
