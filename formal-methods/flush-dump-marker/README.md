# Flush/dump command ownership model

This model checks the ownership of a tracer-flare dump command when it is
queued behind a `PendingTraceBuffer` flush command.

Both commands share the pending-trace queue. When the worker processes the
flush marker, `WriteDrain` drains every following element by calling
`Element.write()`. A dump marker is a `CommandElement`, whose `write()` method
is a no-op. The marker therefore disappears without incrementing
`dumpCounter`, leaving `TracerDump.prepareForFlare()` waiting indefinitely.

Run from the repository root:

```shell
mkdir -p /tmp/dd-trace-java-tlc/flush-dump-marker
java -XX:+UseParallelGC -jar /tmp/tla2tools-1.7.4.jar \
  -config formal-methods/flush-dump-marker/FlushDumpMarker.cfg \
  -metadir /tmp/dd-trace-java-tlc/flush-dump-marker \
  formal-methods/flush-dump-marker/FlushDumpMarker.tla
```

The current implementation produces a four-state `WaitingDumpHasOwner`
invariant violation.

The corresponding deterministic Java regression is
`PendingTraceBufferTest.flushDrainDoesNotConsumeTracerDumpMarker`. It blocks
the worker, queues a flush and then a flare dump, and releases the worker. The
flush completes, but flare preparation remains stuck because its marker was
silently consumed. On the unfixed implementation it fails with a
`TimeoutException` while waiting for flare preparation to complete.
