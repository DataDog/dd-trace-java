# Pending trace flush-drain ownership model

This model checks the queue ownership flag when `PendingTraceBuffer` bulk-drains
elements after a flush marker.

The normal worker path clears `isEnqueued` before writing a trace. The bulk
`WriteDrain` path writes without clearing it. If a child span finishes after
that flush, the trace believes it is already queued and cannot be re-enqueued.

Run from the repository root:

```shell
mkdir -p /tmp/dd-trace-java-tlc/flush-drain-ownership
java -XX:+UseParallelGC -jar /tmp/tla2tools-1.7.4.jar \
  -config formal-methods/pending-trace-flush/FlushDrainOwnership.cfg \
  -metadir /tmp/dd-trace-java-tlc/flush-drain-ownership \
  formal-methods/pending-trace-flush/FlushDrainOwnership.tla
```

The current implementation produces a three-state `PendingChildHasWriter`
invariant violation.

The corresponding deterministic Java regression is
`PendingTraceBufferTest.flushDrainClearsEnqueuedStateBeforeWriting`. It blocks
the worker, puts a flush marker ahead of a pending trace, and then releases the
worker so `WriteDrain` handles the trace. On the unfixed implementation it
fails with:

```text
a trace written by the flush drain must be eligible for re-enqueue
expected: <0> but was: <1>
```

The stale flag means a child that finishes after `CoreTracer.flush()` cannot
place its trace back into the pending buffer, so the child span is never sent.
