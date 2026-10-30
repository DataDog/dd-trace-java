# Single-span-sampling flush model

This model checks whether `TraceProcessingWorker.flush()` covers traces that
were already accepted into its upstream single-span-sampling queue.

The sampling worker moves sampled spans into the primary serialization queue.
However, `flush()` inserts its barrier directly into that primary queue. The
serializer can therefore acknowledge the flush while an older accepted trace
is still being sampled. `RemoteWriter.close()` trusts that acknowledgement and
then interrupts the sampling worker, so shutdown can lose sampled spans.

Run from the repository root:

```shell
mkdir -p /tmp/dd-trace-java-tlc/span-sampling-flush
java -XX:+UseParallelGC -jar /tmp/tla2tools-1.7.4.jar \
  -config formal-methods/span-sampling-flush/SpanSamplingFlush.cfg \
  -metadir /tmp/dd-trace-java-tlc/span-sampling-flush \
  formal-methods/span-sampling-flush/SpanSamplingFlush.tla
```

The current implementation violates `FlushCoversAcceptedCandidate` after the
serializer acknowledges a flush while the accepted trace remains in the
sampling stage.

The corresponding deterministic Java regression is
`TraceProcessingWorkerTest.flushWaitsForInFlightSingleSpanSampling`. It blocks
the sampler after the trace has been accepted, calls `flush()`, and observes
that the call returns successfully before the trace reaches the payload
dispatcher.
