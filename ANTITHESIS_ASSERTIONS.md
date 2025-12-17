# Antithesis Assertions in dd-trace-java

This document describes the Antithesis assertions added to track trace loss, API sending failures, and telemetry data loss.

## Overview

Antithesis assertions have been added to multiple classes in the trace writing pipeline and telemetry system to detect when traces/telemetry are lost or fail to send to the API. These assertions help ensure the reliability of trace collection, telemetry reporting, and transmission at every stage of the process.

## Added Assertions

### Overview by Location

**Telemetry System:**
- **TelemetryClient** - Monitors telemetry HTTP requests, failures, and network issues
- **TelemetryRouter** - Tracks routing failures and endpoint failover

**Trace System:**
- **DDAgentApi** - Monitors agent communication, HTTP responses, and network failures
- **PayloadDispatcherImpl** - Tracks trace sending to the API and pre-send drops
- **RemoteWriter** - Tracks buffer overflow and shutdown scenarios

---

## TelemetryClient Assertions (Telemetry Sending Layer)

### T1. Telemetry Activity Tracking (`reachable` assertion)

**Location:** `TelemetryClient.sendHttpRequest()` method (line 102)

**Property:** `"Telemetry sending is exercised"`

**Type:** `Assert.reachable()`

**Purpose:** Verifies that telemetry sending code is being exercised during testing.

---

### T2. Telemetry Success Validation (`always` assertion) 🔴 **CRITICAL**

**Location:** `TelemetryClient.sendHttpRequest()` method, success path (line 153)

**Property:** `"Telemetry requests should always succeed - no telemetry data should be lost"`

**Type:** `Assert.always()`

**Purpose:** Asserts that ALL telemetry requests should succeed. When this fails, it indicates that **telemetry data is being dropped** instead of being retried or buffered.

**Details Captured:**
- `request_type`: Type of telemetry request (app-started, app-closing, etc.)
- `http_status`: HTTP response code
- `http_message`: HTTP status message
- `url`: Telemetry endpoint URL
- `success`: Whether request succeeded

**The Problem This Detects:**
Your warning message: `"Got FAILURE sending telemetry request"` - indicates telemetry data is being **dropped** without retry mechanism.

---

### T3. Telemetry HTTP Failure Detection (`unreachable` assertion) 🔴

**Location:** `TelemetryClient.sendHttpRequest()` method, non-success response (line 140)

**Property:** `"Telemetry HTTP request failed - telemetry data should not be dropped, should retry"`

**Type:** `Assert.unreachable()`

**Purpose:** Marks the HTTP failure path as unreachable, indicating telemetry data loss. **This is the exact issue you're experiencing** - failures cause data to be dropped instead of retried.

**Details Captured:**
- `request_type`: Type of telemetry request
- `http_status`: Error status code
- `http_message`: Error message
- `url`: Endpoint URL
- `reason`: "http_error_response"

---

### T4. Telemetry Network Exception Prevention (`unreachable` assertion) 🔴

**Location:** `TelemetryClient.sendHttpRequest()` method, IOException catch (line 171)

**Property:** `"Telemetry network/IO failure - telemetry data should not be dropped, should retry"`

**Type:** `Assert.unreachable()`

**Purpose:** Marks network failures as unreachable. When triggered, indicates telemetry data is being lost due to connectivity issues **without retry**.

**Details Captured:**
- `request_type`: Type of telemetry request
- `exception_type`: Exception class name
- `exception_message`: Exception details
- `url`: Endpoint URL
- `reason`: "network_io_exception"

---

### T5. Telemetry 404 Tracking (`sometimes` assertion)

**Location:** `TelemetryClient.sendHttpRequest()` method, 404 response (line 122)

**Property:** `"Telemetry endpoint returns 404 - endpoint may be disabled"`

**Type:** `Assert.sometimes()`

**Purpose:** Tracks when telemetry endpoint is disabled (404). This may be acceptable in some configurations.

**Details Captured:**
- `request_type`: Type of telemetry request
- `url`: Endpoint URL
- `reason`: "endpoint_disabled_404"

---

## TelemetryRouter Assertions (Telemetry Routing Layer)

### T6. Telemetry Routing Success (`always` assertion) 🔴 **CRITICAL**

**Location:** `TelemetryRouter.sendRequest()` method (line 56)

**Property:** `"Telemetry routing should always succeed - failures indicate data loss without retry mechanism"`

**Type:** `Assert.always()`

**Purpose:** Validates that telemetry routing succeeds. This is the **top-level** assertion that catches all telemetry failures and proves that **current failures result in data loss**.

**Details Captured:**
- `result`: SUCCESS, FAILURE, NOT_FOUND, or INTERRUPTED
- `current_client`: "agent" or "intake"
- `request_failed`: Boolean
- `has_fallback`: Whether fallback client exists
- `url`: Current endpoint URL

---

### T7. Agent Telemetry Failover Tracking (`unreachable` assertion) 🔴

**Location:** `TelemetryRouter.sendRequest()` method, agent failure (line 70)

**Property:** `"Agent telemetry endpoint failed - switching to intake but current request data is lost"`

**Type:** `Assert.unreachable()`

**Purpose:** Tracks when agent telemetry fails and router switches to intake. **Critical:** The current request data is LOST during this failover - only future requests go to intake.

**Details Captured:**
- `result`: Failure result type
- `url`: Agent endpoint URL
- `has_intake_fallback`: Whether intake fallback is available
- `reason`: "agent_telemetry_failure"

---

### T8. Intake Telemetry Failover Tracking (`unreachable` assertion) 🔴

**Location:** `TelemetryRouter.sendRequest()` method, intake failure (line 90)

**Property:** `"Intake telemetry endpoint failed - switching to agent but current request data is lost"`

**Type:** `Assert.unreachable()`

**Purpose:** Tracks when intake telemetry fails and router switches back to agent. **Critical:** The current request data is LOST during this failover.

**Details Captured:**
- `result`: Failure result type
- `url`: Intake endpoint URL
- `will_fallback_to_agent`: Boolean
- `reason`: "intake_telemetry_failure"

---

## DDAgentApi Assertions (Agent Communication Layer)

### 1. Agent API Activity Tracking (`reachable` + `sometimes` assertions)

**Location:** `DDAgentApi.sendSerializedTraces()` method start (line 97-100)

**Properties:**
- `"DDAgentApi trace sending is exercised"` (reachable)
- `"Traces are being sent through DDAgentApi"` (sometimes)

**Type:** `Assert.reachable()` + `Assert.sometimes()`

**Purpose:** Verifies that the DDAgentApi code path is being exercised and traces are flowing through the agent API layer.

---

### 2. Agent Detection Validation (`unreachable` assertion) 🔴

**Location:** `DDAgentApi.sendSerializedTraces()` method, agent detection failure (line 107)

**Property:** `"Datadog agent should always be detected - agent communication failure"`

**Type:** `Assert.unreachable()`

**Purpose:** Asserts that the Datadog agent should always be discoverable. If the agent cannot be detected, traces will be lost with a 404 error.

**Details Captured:**
- `trace_count`: Number of traces that cannot be sent
- `payload_size_bytes`: Size of the payload
- `agent_url`: The agent URL being contacted
- `failure_reason`: "agent_not_detected"

**When This Occurs:**
- Agent is not running
- Agent is unreachable (network/firewall issues)
- Incorrect agent URL configuration
- Agent discovery mechanism failure

---

### 3. HTTP Response Success Validation (`always` assertion) 🔴 **CRITICAL**

**Location:** `DDAgentApi.sendSerializedTraces()` method, after HTTP call (line 149)

**Property:** `"HTTP response from Datadog agent should always be 200 - API communication failure"`

**Type:** `Assert.always()`

**Purpose:** Validates that every HTTP response from the agent is successful (200 OK). This is the primary assertion for detecting API-level failures.

**Details Captured:**
- `trace_count`: Number of traces being sent
- `payload_size_bytes`: Size of the payload
- `http_status`: HTTP status code received
- `http_message`: HTTP status message
- `success`: Boolean indicating if status is 200
- `agent_url`: Full URL of the traces endpoint

**When This Fails:**
- Agent returns error status codes (400, 413, 500, 503, etc.)
- Authentication/authorization failures
- Agent overload or resource exhaustion
- Malformed requests

---

### 4. HTTP Error Path Unreachability (`unreachable` assertion) 🔴

**Location:** `DDAgentApi.sendSerializedTraces()` method, non-200 response branch (line 163)

**Property:** `"Non-200 HTTP response from agent indicates API failure - traces may be lost"`

**Type:** `Assert.unreachable()`

**Purpose:** Marks the non-200 response code path as unreachable. When reached, indicates traces are being rejected by the agent.

**Details Captured:**
- `trace_count`: Number of traces rejected
- `payload_size_bytes`: Size of rejected payload
- `http_status`: Error status code
- `http_message`: Error message from agent
- `failure_reason`: "http_error_response"

**Common Status Codes:**
- 400: Bad Request (malformed payload)
- 413: Payload Too Large
- 429: Too Many Requests (rate limiting)
- 500: Internal Server Error
- 503: Service Unavailable (agent overloaded)

---

### 5. Network Exception Prevention (`unreachable` assertion) 🔴

**Location:** `DDAgentApi.sendSerializedTraces()` method, IOException catch block (line 199)

**Property:** `"Network/IO exceptions should not occur when sending to agent - indicates connectivity issues"`

**Type:** `Assert.unreachable()`

**Purpose:** Asserts that network/IO exceptions should never occur when communicating with the agent. These indicate infrastructure or connectivity problems.

**Details Captured:**
- `trace_count`: Number of traces that failed to send
- `payload_size_bytes`: Size of the payload
- `exception_type`: Full class name of the exception
- `exception_message`: Exception message
- `agent_url`: Agent URL being contacted
- `failure_reason`: "network_io_exception"

**When This Occurs:**
- Network connectivity issues
- Connection timeouts
- DNS resolution failures
- Socket errors
- SSL/TLS handshake failures

---

## PayloadDispatcherImpl Assertions (Trace Serialization Layer)

### 6. Payload Dispatcher Activity Tracking (`reachable` + `sometimes` assertions)

**Location:** `PayloadDispatcherImpl.accept()` method (line 110-113)

**Properties:**
- `"Trace sending code path is exercised"` (reachable)
- `"Traces are being sent to the API"` (sometimes)

**Type:** `Assert.reachable()` + `Assert.sometimes()`

**Purpose:** Verifies that the PayloadDispatcher code path is being exercised and traces are flowing through.

---

### 7. Trace Sending Success (`always` assertion)

**Location:** `PayloadDispatcherImpl.accept()` method (line 136)

**Property:** `"Trace sending to API should always succeed - no traces should be lost"`

**Type:** `Assert.always()`

**Purpose:** Asserts that every trace sending attempt should succeed. If this assertion fails, it indicates that traces are being lost due to API failures.

**Details Captured:**
- `trace_count`: Number of traces in the payload
- `payload_size_bytes`: Size of the payload in bytes
- `success`: Whether the send was successful
- `exception`: Exception class name (if present)
- `exception_message`: Exception message (if present)
- `http_status`: HTTP response status code (if present)

### 8. Send Failure Path (`unreachable` assertion)

**Location:** `PayloadDispatcherImpl.accept()` method, failure branch (line 159)

**Property:** `"Trace sending failure path should never be reached - indicates traces are being lost"`

**Type:** `Assert.unreachable()`

**Purpose:** Marks the failure path as something that should never occur. When this path is reached, it indicates traces are being lost due to send failures.

**Details Captured:**
- `trace_count`: Number of traces that failed to send
- `payload_size_bytes`: Size of failed payload
- `exception`: Exception class name (if present)
- `exception_message`: Exception message (if present)
- `http_status`: HTTP response status code (if present)

### 9. Trace Drop Prevention (`unreachable` assertion)

**Location:** `PayloadDispatcherImpl.onDroppedTrace()` method (line 69)

**Property:** `"Traces should not be dropped before attempting to send - indicates buffer overflow or backpressure"`

**Type:** `Assert.unreachable()`

**Purpose:** Asserts that traces should never be dropped before even attempting to send them. Drops indicate buffer overflow, backpressure, or resource exhaustion.

**Details Captured:**
- `span_count`: Number of spans in the dropped trace
- `total_dropped_traces`: Cumulative count of dropped traces
- `total_dropped_spans`: Cumulative count of dropped spans

---

## RemoteWriter Assertions (Buffer and Lifecycle Layer)

### 10. Writer State Validation (`always` assertion) 🔴 **CRITICAL**

**Location:** `RemoteWriter.write()` method, start of method (line 79)

**Property:** `"Writer should never be closed when attempting to write traces"`

**Type:** `Assert.always()`

**Purpose:** Proactively validates that the writer is in a valid state (not closed) whenever traces are being written. This assertion catches improper usage where traces are written after shutdown or during shutdown race conditions. This is a **preventive assertion** that checks every write attempt.

**Details Captured:**
- `writer_closed`: Boolean indicating if writer is closed
- `trace_size`: Number of traces being written
- `has_traces`: Whether the trace list is non-empty

**When This Fails:**
- Application attempts to write traces after calling `close()`
- Race condition between shutdown and trace generation
- Improper lifecycle management
- Indicates a bug in the calling code or shutdown sequencing

**Importance:** This is a critical assertion because writing to a closed writer indicates a fundamental problem with lifecycle management that could lead to:
- Lost traces during shutdown
- Inconsistent application state
- Potential resource leaks

---

### 11. Buffer Overflow Detection (`unreachable` assertion) 🔴 **CRITICAL**

**Location:** `RemoteWriter.write()` method, DROPPED_BUFFER_OVERFLOW case (line 117)

**Property:** `"Buffer overflow should never occur - traces are being dropped due to backpressure"`

**Type:** `Assert.unreachable()`

**Purpose:** Asserts that buffer overflow should NEVER happen. This indicates that traces are being generated faster than they can be processed and serialized, resulting in dropped traces. This is a critical issue that indicates system overload or insufficient buffer capacity.

**Details Captured:**
- `trace_size`: Number of traces being dropped
- `span_count`: Total number of spans in the dropped traces
- `sampling_priority`: Sampling priority of the trace
- `buffer_capacity`: Current buffer capacity
- `reason`: "buffer_overflow_backpressure"

**When This Occurs:**
- Internal processing queue is full (primary, secondary, or span sampling queue)
- Traces are being generated faster than serialization can occur
- System is under heavy load or experiencing backpressure
- Buffer size may be insufficient for the workload

---

### 12. Shutdown Trace Drop Tracking (`sometimes` assertion)

**Location:** `RemoteWriter.write()` method, closed writer case (line 94)

**Property:** `"Traces are dropped due to writer shutdown - tracking shutdown behavior"`

**Type:** `Assert.sometimes()`

**Purpose:** Tracks when traces are dropped because the writer has been shut down. This helps understand shutdown behavior and whether traces are being lost during application shutdown sequences.

**Details Captured:**
- `trace_size`: Number of traces being dropped
- `span_count`: Total number of spans in the dropped traces
- `reason`: "writer_closed_during_shutdown"

**When This Occurs:**
- Application is shutting down
- Writer.close() has been called
- Traces are still being generated after shutdown initiated
- Can indicate timing issues in shutdown sequences

## How Antithesis Uses These Assertions

When running under Antithesis testing:

1. **Property Aggregation:** All assertions with the same `message` are aggregated into a single test property in the triage report.

2. **Failure Detection:** 
   - `always()` assertions that evaluate to `false` will flag the property as failing
   - `unreachable()` assertions that are reached will flag the property as failing
   - `sometimes()` assertions that never evaluate to `true` will flag the property as failing

3. **Exploration Guidance:** Antithesis uses these assertions as hints to explore states that might trigger failures, making bug detection more efficient.

4. **Non-Terminating:** Unlike traditional assertions, Antithesis assertions do not terminate the program when they fail. This allows Antithesis to potentially escalate the failure into more severe bugs.

## Expected Behavior

### In a Healthy System

**Telemetry System:**
- ✅ `"Telemetry sending is exercised"` - Should pass (reached at least once)
- ✅ `"Telemetry requests should always succeed"` - Should pass (all succeed) 🔴 **CRITICAL**
- ✅ `"Telemetry HTTP request failed - should retry"` - Should pass (never reached) 🔴
- ✅ `"Telemetry network/IO failure - should retry"` - Should pass (never reached) 🔴
- ✅ `"Telemetry routing should always succeed"` - Should pass (all succeed) 🔴 **CRITICAL**
- ✅ `"Agent telemetry endpoint failed"` - Should pass (never reached) 🔴
- ✅ `"Intake telemetry endpoint failed"` - Should pass (never reached) 🔴
- ℹ️ `"Telemetry endpoint returns 404"` - May occur if endpoint disabled

**Trace DDAgentApi Layer:**
- ✅ `"DDAgentApi trace sending is exercised"` - Should pass (reached at least once)
- ✅ `"Traces are being sent through DDAgentApi"` - Should pass (reached at least once)
- ✅ `"Datadog agent should always be detected"` - Should pass (agent always detectable) 🔴
- ✅ `"HTTP response from Datadog agent should always be 200"` - Should pass (all responses 200) 🔴 **CRITICAL**
- ✅ `"Non-200 HTTP response from agent indicates API failure"` - Should pass (never reached) 🔴
- ✅ `"Network/IO exceptions should not occur"` - Should pass (never reached) 🔴

**PayloadDispatcherImpl Layer:**
- ✅ `"Trace sending code path is exercised"` - Should pass (reached at least once)
- ✅ `"Traces are being sent to the API"` - Should pass (reached at least once)
- ✅ `"Trace sending to API should always succeed"` - Should pass (all sends succeed)
- ✅ `"Trace sending failure path should never be reached"` - Should pass (never reached)
- ✅ `"Traces should not be dropped before attempting to send"` - Should pass (never reached)

**RemoteWriter Layer:**
- ✅ `"Writer should never be closed when attempting to write traces"` - Should pass (writer always open) 🔴 **CRITICAL**
- ✅ `"Buffer overflow should never occur"` - Should pass (never reached) 🔴 **CRITICAL**
- ℹ️ `"Traces are dropped due to writer shutdown"` - May or may not occur depending on shutdown timing

### When Telemetry Is Lost ⚠️ **YOUR ISSUE**

If telemetry is being lost (your current issue with `"Got FAILURE sending telemetry request"`), you'll see these failures:

**Telemetry HTTP/Network Failures:**
- ❌ `"Telemetry requests should always succeed"` - Will fail on any telemetry failure 🔴 **CRITICAL**
  - This is the top-level assertion proving telemetry data loss
  - Shows request type, HTTP status, and endpoint
- ❌ `"Telemetry HTTP request failed - should retry"` - Will fail when HTTP errors occur 🔴
  - Indicates telemetry dropped due to HTTP errors (5xx, 4xx)
  - Shows status code and error message
- ❌ `"Telemetry network/IO failure - should retry"` - Will fail on connectivity issues 🔴
  - Indicates telemetry dropped due to network problems
  - Shows exception type and message

**Telemetry Routing Failures:**
- ❌ `"Telemetry routing should always succeed"` - Will fail when routing fails 🔴 **CRITICAL**
  - Proves current implementation drops data instead of retrying
  - Shows current client (agent/intake) and failure details
- ❌ `"Agent telemetry endpoint failed - current request data is lost"` - Will fail when agent endpoint fails 🔴
  - Router switches to intake but **current request is dropped**
  - Shows whether fallback is available
- ❌ `"Intake telemetry endpoint failed - current request data is lost"` - Will fail when intake endpoint fails 🔴
  - Router switches to agent but **current request is dropped**
  - Future requests use new endpoint, but current data is lost

**Key Finding:** The assertions prove that when telemetry fails, the **current request is DROPPED** - the router only changes the endpoint for **future** requests. This is why you see `"Got FAILURE"` warnings - there's no retry or buffering mechanism.

---

### When Traces Are Lost

If traces are being lost, you'll see failures in the triage report:

**Agent Communication Failures (DDAgentApi):**
- ❌ `"Datadog agent should always be detected"` - Will fail if agent is unreachable 🔴
  - Indicates agent not running, network issues, or configuration problems
  - Provides agent URL and detection details
- ❌ `"HTTP response from Datadog agent should always be 200"` - Will fail on any error status 🔴 **CRITICAL**
  - Shows HTTP status code, message, and agent URL
  - Indicates agent overload, rate limiting, or request errors
- ❌ `"Non-200 HTTP response from agent indicates API failure"` - Will fail when agent rejects traces 🔴
  - Provides HTTP status codes (400, 413, 429, 500, 503, etc.)
- ❌ `"Network/IO exceptions should not occur"` - Will fail on network errors 🔴
  - Shows exception type and message
  - Indicates connectivity, timeout, or DNS issues

**API Send Failures (PayloadDispatcherImpl):**
- ❌ `"Trace sending to API should always succeed"` - Will fail with details about failed sends
- ❌ `"Trace sending failure path should never be reached"` - Will fail, showing this path was reached

**Buffer/Queue Issues:**
- ❌ `"Buffer overflow should never occur"` - Will fail if backpressure causes drops 🔴 **CRITICAL**
  - Indicates system overload or insufficient buffer capacity
  - Provides buffer capacity and trace details
- ❌ `"Traces should not be dropped before attempting to send"` - May fail if drops occur in PayloadDispatcher

**Lifecycle/Shutdown Issues:**
- ❌ `"Writer should never be closed when attempting to write traces"` - Will fail if traces written to closed writer 🔴 **CRITICAL**
  - Indicates race condition in shutdown sequence
  - Shows improper lifecycle management
  - Provides details about writer state and trace being written
- ⚠️ `"Traces are dropped due to writer shutdown"` - Will show in report if shutdown timing causes trace loss
  - Helps identify if shutdown sequence needs improvement
  - May be acceptable depending on shutdown strategy
  - Works in conjunction with the writer state validation above

The `details` captured in failed assertions will provide diagnostic information including trace counts, payload sizes, exceptions, HTTP status codes, buffer capacity, and sampling priority.

## Dependencies

- **Antithesis SDK:** `com.antithesis:sdk:1.4.5` (bundled in tracer JAR) - [Available on Maven Central](https://repo1.maven.org/maven2/com/antithesis/sdk/)
- **Jackson:** Already available transitively in the project

### Bundled SDK

The Antithesis SDK is configured as an `implementation` dependency, which means:

- ✅ **Bundled in final JAR** - SDK classes included in the dd-trace-java agent
- ✅ **Always available** - No ClassNotFoundException at runtime
- ✅ **Works everywhere** - Assertions compiled and available in all environments

### Using Antithesis Assertions

The Antithesis SDK (version 1.4.5) is publicly available on Maven Central and is bundled with the tracer.

**In normal runtime (production/development):**
- Assertions are present in the code but have **minimal performance impact**
- According to [Antithesis documentation](https://antithesis.com/docs/properties_assertions/assertions/), the SDK is designed to run safely in production
- Assertions become no-ops when not running in Antithesis environment

**In Antithesis testing environment:**
- Antithesis runtime automatically detects and evaluates all assertions
- Generates triage reports showing which properties passed/failed
- Provides detailed bug reports with reproducible scenarios
- Contact Antithesis at [antithesis.com](https://antithesis.com) for access to their testing platform

## Complete Pipeline Coverage Summary

The assertions provide comprehensive coverage across telemetry and trace pipelines:

### Telemetry Pipeline

```
Application Telemetry Events
       ↓
[TelemetryRouter] ← Assertions T6-T8
  • Routing success validation
  • Agent failover tracking
  • Intake failover tracking
  • ⚠️ PROVES: Current request dropped on failover
       ↓
[TelemetryClient] ← Assertions T1-T5
  • Activity tracking
  • HTTP success validation
  • Failure detection (HTTP errors)
  • Network exception handling
  • 404 endpoint tracking
  • ⚠️ PROVES: No retry on failure
       ↓
[Telemetry Endpoint] → Datadog Backend
```

### Trace Pipeline

```
Application Threads
       ↓
[CoreTracer] → Sampling decision
       ↓
[RemoteWriter] ← Assertions 10-12
  • Writer state validation
  • Buffer overflow detection
  • Shutdown tracking
       ↓
[TraceProcessingWorker] → Serialization queues
       ↓
[PayloadDispatcherImpl] ← Assertions 6-9
  • Activity tracking
  • Trace sending validation
  • Failure path detection
  • Pre-send drop prevention
       ↓
[DDAgentApi] ← Assertions 1-5
  • Agent detection
  • HTTP response validation
  • Network exception handling
       ↓
[Datadog Agent] → Backend
```

### Assertion Count by Category

| Category | Count | Criticality | Status |
|----------|-------|-------------|--------|
| **Telemetry Communication** | 5 | 🔴 **CRITICAL** | ⚠️ **DROPS DATA** |
| **Telemetry Routing** | 3 | 🔴 **CRITICAL** | ⚠️ **DROPS DATA** |
| **Agent Communication** | 5 | 🔴 **CRITICAL** | ✅ Has retries |
| **Trace Serialization** | 4 | ❌ High | ✅ Good |
| **Buffer Management** | 2 | 🔴 **CRITICAL** | ✅ Good |
| **Lifecycle Management** | 1 | 🔴 **CRITICAL** | ✅ Good |
| **Total** | **20** | - | - |

### Key Properties Monitored

**Telemetry System (YOUR ISSUE):**
1. ⚠️ **Telemetry Data Loss**: Telemetry dropped on HTTP/network failures
2. ⚠️ **No Retry Mechanism**: Failed requests are not retried or buffered
3. ⚠️ **Failover Data Loss**: Current request dropped during endpoint switching

**Trace System:**
4. **Agent Availability**: Agent must be detectable and reachable
5. **HTTP Success**: All agent responses must be 200 OK
6. **Network Stability**: No IO/network exceptions should occur
7. **Buffer Capacity**: No overflow or backpressure drops
8. **Lifecycle Correctness**: No writes to closed writer
9. **End-to-End Success**: All traces must be successfully sent

## References

- [Antithesis Assertions Documentation](https://antithesis.com/docs/properties_assertions/assertions/)
- [Java SDK Reference](https://antithesis.com/docs/generated/sdk/java/com/antithesis/sdk/Assert.html)

