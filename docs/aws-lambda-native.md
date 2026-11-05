# Native Lambda tracing

The Java tracer owns invocation spans, context extraction, inferred spans, handler errors,
AppSec callbacks, payload capture, DSM consumption and upstream span pointers. It uses the
configured trace writer and existing DSM transport. There are no lifecycle HTTP calls,
capability probes, identity announcements or completion acknowledgments in the native path.

## Configuration

Lambda tracing is always native at this final removal stage. `DD_UNIVERSAL_INSTRUMENTATION`
is deprecated. Explicit `true` logs a diagnostic and cannot restore the removed legacy path.
Rollback requires a prior supported tracer release. This removal draft is blocked on the
native-default release, deployment certification and an agreed rollback support window.

Java propagation, sampling, AppSec and service settings apply. Optional settings:

- `DD_CAPTURE_LAMBDA_PAYLOAD` (false), `DD_CAPTURE_LAMBDA_PAYLOAD_MAX_DEPTH` (10).
- `DD_DATA_STREAMS_ENABLED`, `DD_DSM_EXCHANGE_NAME`, `DD_DSM_KAFKA_GROUP`.
- `DD_TRACE_AWS_SERVICE_REPRESENTATION_ENABLED` (true), service mappings and service removal.
- Existing pointer setting `DD_TRACE_AWSADD_SPAN_POINTERS` (true; spelling retained).

The extension's existing trace and DSM intake remain supported transport mechanisms. There
is no replacement lifecycle protocol and no extension capability required to create spans.
That does not establish complete platform feature compatibility with a released extension.

## Current limits and rollout blockers

The extension receives AWS platform init/restore/runtimeDone/report events and continues to
collect platform metrics and logs. Java handler advice cannot observe those events. The
current drafts do not implement the following platform correlation or compatibility work:

- Correlating authoritative cold-start and SnapStart spans with native Java invocation traces.
  The existing intake recognizes Node/Python load spans and legacy Java placeholders; it
  does not join real Java invocation spans with platform events by request ID.
- Correlated timeout/OOM/process-death spans when the JVM dies before sending the invocation.
  A completed trace cannot supply identity after process death, and a first-call flag or JVM
  uptime cannot substitute for authoritative Lambda init/restore timings.
- Enhanced application-error metrics for returned 5xx when platform status is success.
  Those metrics must survive trace sampling; retained spans alone cannot provide parity.
- Single DSM consumption ownership with the extension Runtime API proxy. Native Java always
  records enabled consumption checkpoints. An existing extension proxy can also consume the
  same payload; DSM with that proxy is not certified. Shared `DD_DATA_STREAMS_ENABLED` does
  not disable one producer independently. LWA/experimental full instrumentation can also
  create duplicate invocation traces and needs separate compatibility validation.

These are blockers for native default and legacy removal, not reasons to add another
lifecycle API. Any platform-only work kept in the extension must use its AWS telemetry and
existing data intake, preserve other runtimes, and be reviewed separately after client work.
No minimum compatible extension release or supported native rollout is claimed yet.

Java measures the instrumented handler interval. It does not rewrite duration from platform
telemetry or add a runtime-duration metric. Review this difference from the legacy platform
interval, including inferred-span timing and client-computed stats, before rollout.

Payload capture redacts sensitive fields of all types before depth-limited serialization,
including JSON-encoded bodies. Review this intentional redaction change. Pointers retain
first-record selection. DSM processes each supported record, and child work continues the
first record's pathway. Invocation-end DSM flush waits at most one second; timing out cannot
guarantee delivery or cancel an already running request. Measure trace/DSM flush overhead.

## Validation

Local tests cover parsing, propagation, activation/cleanup, retained/dropped traces, errors,
AppSec callbacks and optional features. Handler tests listen on the extension lifecycle port
and require zero requests for successful, nested and throwing invocations. DSM tests verify
that dropping the trace does not drop consumption checkpoints.

Before changing defaults, compare legacy/native deployments across supported JDKs and
architectures, stream/POJO handlers, concurrency, warm/cold/proactive init, SnapStart and
Managed Instances. Include old/current extensions, tracing disabled, sampling, AppSec/proxy
blocking, DSM downstream continuity, timeout/OOM, partial flushes and runtime freeze. Check
span/error/metric counts, parent IDs, stats ownership and latency/memory overhead. All
platform gaps above must be resolved or receive an explicit compatibility decision.

The migration plan and PR tracker are local documents outside this repository. See
[APMSVLS-689](https://datadoghq.atlassian.net/browse/APMSVLS-689) for release evidence.
Other runtimes retain their existing extension lifecycle APIs until their migrations finish.
