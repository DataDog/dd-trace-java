# DSM Test Patterns

## Reliable Unit Test Pattern (CapturingPayloadWriter)

```groovy
def features = Stub(DDAgentFeaturesDiscovery) { supportsDataStreams() >> true }
def timeSource = new ControllableTimeSource()
def sink = Mock(Sink)
def payloadWriter = new CapturingPayloadWriter()
def traceConfig = Mock(TraceConfig) { isDataStreamsEnabled() >> true }

def dataStreams = new DefaultDataStreamsMonitoring(sink, features, timeSource, { traceConfig },
    payloadWriter, DEFAULT_BUCKET_DURATION_NANOS)
dataStreams.start()

// ... add data ...

timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS)
dataStreams.report()

// Use PollingConditions to wait for async processing
conditions.eventually {
    assert dataStreams.inbox.isEmpty()
    assert payloadWriter.buckets.size() == 1
}

// Verify bucket contents
with(payloadWriter.buckets.get(0)) { ... }
```

## Key Timing Details
- Must advance time by at least `DEFAULT_BUCKET_DURATION_NANOS` before report
- `report()` only flushes buckets older than the current bucket
- `close()` flushes ALL buckets including current
- Use `PollingConditions(timeout: 1)` for async wait

## Deduplication Testing
- `reportKafkaConfig()` deduplicates based on `KafkaConfigReport.equals()` (type + config map)
- Timestamp and serviceNameOverride are NOT part of equals/hashCode
- Dedup is global (ConcurrentHashMap), not per-bucket
- `clear()` resets the dedup cache
