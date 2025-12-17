# Trace Loss Tracking with Antithesis Assertions

## Overview

This document describes the simplified Antithesis assertion strategy implemented to track trace loss in dd-trace-java.

## Implementation

Assertions were added at 3 strategic points in the trace pipeline to provide complete visibility into where and why traces are lost:

### 1. CoreTracer.write() - Sampling Decision Point

**Location:** `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java`

**Purpose:** Track traces at the sampling decision point

**Assertions:**
- `trace_accepted_by_sampling` - Traces that passed sampling and will be sent
- `trace_dropped_by_sampling` - Traces dropped due to sampling decision

**Data Captured:**
- `decision`: "accepted" or "dropped_sampling"
- `trace_id`: Unique trace identifier
- `span_count`: Number of spans in the trace
- `sampling_priority`: Sampling priority value

### 2. RemoteWriter.write() - Buffer Acceptance Point

**Location:** `dd-trace-core/src/main/java/datadog/trace/common/writer/RemoteWriter.java`

**Purpose:** Track traces at buffer acceptance and detect drops due to overflow or policy

**Assertions:**
- `trace_enqueued_for_send` - Traces successfully enqueued for serialization
- `trace_dropped_buffer_overflow` - Traces dropped due to full buffer
- `trace_dropped_by_policy` - Traces dropped by policy rules
- `trace_dropped_writer_closed` - Traces dropped during shutdown

**Data Captured:**
- `decision`: "enqueued", "dropped_buffer_overflow", "dropped_policy", or "dropped_shutdown"
- `trace_id`: Unique trace identifier (when available)
- `span_count`: Number of spans in the trace
- `sampling_priority`: Sampling priority value (when available)

### 3. PayloadDispatcherImpl.accept() - HTTP Send Point

**Location:** `dd-trace-core/src/main/java/datadog/trace/common/writer/PayloadDispatcherImpl.java`

**Purpose:** Track actual HTTP sends to the agent and detect failures

**Assertions:**
- `trace_payloads_being_sent` - All send attempts (before HTTP call)
- `traces_sent_successfully` - Traces successfully sent to agent
- `traces_failed_to_send` - Traces that failed to send via HTTP

**Data Captured:**
- `decision`: "sent_success" or "dropped_send_failed"
- `trace_count`: Number of traces in the payload
- `payload_size_bytes`: Size of the payload in bytes
- `http_status`: HTTP response status code
- `dropped_traces_in_payload`: Count of traces already dropped before this send
- `dropped_spans_in_payload`: Count of spans already dropped before this send
- `has_exception`: Whether an exception occurred (for failures)

## Complete Trace Flow

```
Application → CoreTracer.write()
                    ↓
            [ASSERTION POINT 1: Sampling]
            ↓                           ↓
      published=true              published=false
            ↓                           ↓
    ✅ trace_accepted_by_sampling   ❌ trace_dropped_by_sampling
            ↓
    RemoteWriter.write()
            ↓
    [ASSERTION POINT 2: Buffer Acceptance]
            ↓
    traceProcessingWorker.publish()
            ↓
    ✅ trace_enqueued_for_send
    OR
    ❌ trace_dropped_buffer_overflow
    ❌ trace_dropped_by_policy
    ❌ trace_dropped_writer_closed
            ↓
    TraceProcessingWorker (batching)
            ↓
    PayloadDispatcherImpl.accept()
            ↓
    [ASSERTION POINT 3: HTTP Send]
            ↓
    🔵 trace_payloads_being_sent
            ↓
    api.sendSerializedTraces()
            ↓                           ↓
    response.success()          !response.success()
            ↓                           ↓
    ✅ traces_sent_successfully     ❌ traces_failed_to_send
```

## Metrics Available After Antithesis Testing

After running Antithesis tests, you will be able to calculate:

### Total Traces Processed
```
Total = trace_accepted_by_sampling + trace_dropped_by_sampling
```

### Total Traces Lost
```
Lost = trace_dropped_by_sampling
     + trace_dropped_buffer_overflow
     + trace_dropped_by_policy
     + trace_dropped_writer_closed
     + traces_failed_to_send
```

### Total Traces Successfully Sent
```
Success = traces_sent_successfully
```

### Loss Rate
```
Loss Rate = (Total Traces Lost / Total Traces Processed) * 100%
```

### Loss Breakdown by Cause
- **Sampling Loss:** `trace_dropped_by_sampling / Total Traces Processed`
- **Buffer Overflow Loss:** `trace_dropped_buffer_overflow / Total Traces Processed`
- **Policy Loss:** `trace_dropped_by_policy / Total Traces Processed`
- **Shutdown Loss:** `trace_dropped_writer_closed / Total Traces Processed`
- **Send Failure Loss:** `traces_failed_to_send / Total Traces Processed`

## Assertion Properties

All assertions use `Assert.sometimes()` which means:
- They track that the condition occurred at least once during testing
- They provide detailed context about each occurrence
- They don't fail the test (they're for tracking, not validation)

## Benefits of This Approach

1. **Clear Tracking:** Each assertion has a unique, descriptive name
2. **Complete Coverage:** Tracks the entire pipeline from sampling to agent
3. **Detailed Context:** Captures relevant metadata at each point
4. **Easy Analysis:** Simple math to calculate loss rates and breakdown
5. **Actionable Data:** Identifies exactly where and why traces are lost

## Example Analysis

After an Antithesis test run, you might see:

```
trace_accepted_by_sampling: 10,000 occurrences
trace_dropped_by_sampling: 90,000 occurrences
trace_enqueued_for_send: 10,000 occurrences
trace_dropped_buffer_overflow: 50 occurrences
traces_sent_successfully: 9,950 occurrences
traces_failed_to_send: 0 occurrences
```

**Analysis:**
- Total traces: 100,000
- Sampling rate: 10% (10,000 accepted / 100,000 total)
- Buffer overflow: 0.05% (50 / 100,000)
- Send success rate: 99.5% (9,950 / 10,000 accepted)
- Overall success rate: 9.95% (9,950 / 100,000 total)

**Conclusion:** 
- Sampling is working as expected (90% drop rate)
- Very low buffer overflow (0.05%)
- Excellent send success rate (99.5%)
- No HTTP failures

## Dependencies

- **Antithesis SDK:** `com.antithesis:sdk:1.4.5` (already configured in `dd-trace-core/build.gradle`)
- The SDK is bundled in the tracer JAR and has minimal performance impact in production

## Running Antithesis Tests

Contact the Antithesis team or refer to their documentation for running tests with these assertions enabled.

## Future Enhancements

Potential improvements:
1. Add `Assert.always()` for critical paths that should never fail
2. Add `Assert.unreachable()` for error paths that should never occur
3. Track additional metadata (e.g., service names, operation names)
4. Add time-based metrics (latency, throughput)

