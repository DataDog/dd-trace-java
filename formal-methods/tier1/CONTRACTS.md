# Tier-1 contract coverage

The Tier-1 suite focuses on ownership, acknowledgement, flush, and close
boundaries where failures can silently lose trace data or leave agent workers
alive.

| Area | Contract | Model | Current result | Java evidence |
|---|---|---|---|---|
| Pending traces | Every live trace has an owner | `TrackerOwnership` | Known violation | `LongRunningTracesTrackerTest.fullTrackerLeavesOverflowTraceForBufferFallback` |
| Pending traces | A late child retains a writer after flush | `FlushDrainOwnership` | Known violation | `PendingTraceBufferTest.flushDrainClearsEnqueuedStateBeforeWriting` |
| Buffer commands | A waiting command remains owned or acknowledged | `FlushDumpMarker` | Known violation | `PendingTraceBufferTest.flushDrainDoesNotConsumeTracerDumpMarker` |
| Writer pipeline | Flush covers accepted upstream sampling work | `SpanSamplingFlush` | Known violation | `TraceProcessingWorkerTest.flushWaitsForInFlightSingleSpanSampling` |
| Context | Continuations finalize once and only when quiescent | `ContinuationLifecycle` | Passes bounded model | Existing context lifecycle tests |
| Remote config | Cached targets were distributed to every healthy listener | `ListenerDistribution` | Known violation | `ProductStateSpecification.listenerFailureDoesNotPreventLaterListenersFromReceivingConfiguration` |
| Telemetry | Accepted events remain queued, retry-buffered, delivered, or policy-dropped | `TelemetryRetryOwnership` | Passes bounded model | Existing `TelemetryServiceTest` retry tests |
| Data streams | `close()` cannot return with its worker alive | `DataStreamsClose` | Known violation | `DefaultDataStreamsMonitoringTest.closeStopsWorkerWhenInboxIsFull` |

## Review rule

Every new asynchronous Tier-1 handoff should identify:

1. the owner before and after the handoff;
2. receiver rejection and capacity behavior;
3. the barrier semantics for flush and close;
4. the terminal states: delivered, explicitly dropped, or cancelled;
5. a deterministic Java schedule for every counterexample.

The shared predicates in `formal-methods/contracts` provide the vocabulary;
individual models remain small so their counterexamples map directly to Java
methods and tests.
