# Integration Plan: OTLP Profile Uploader → Tracer OTLP Infrastructure

## Current State

The `OtlpProfileUploader` (in `profiling-uploader`) is completely standalone:
- Builds its own `OkHttpClient` with its own dispatcher
- Hand-rolls HTTP requests, gzip, headers, retry
- Uses ad-hoc config keys (`profiling.otlp.*`) in `ProfilingConfig`
- No telemetry integration
- No retry policy (fails once, logs, gives up)
- No connection to `SharedCommunicationObjects`

The rest of the tracer has a mature OTLP export framework in `dd-trace-core/.../otlp/`:
- `OtlpSender` interface → `OtlpGrpcSender` / `OtlpHttpSender`
- `OtlpPayload` (ByteBuffer + content-type)
- `OtlpSenderSupport` (retry via `HttpRetryPolicy`, rate-limited logging)
- `OtlpTelemetry` (export attempt/success/failure counters)
- Per-signal config in `OtlpConfig` + `Config` getters
- Per-signal service classes (`OtlpLogsService`, `OtlpMetricsService`, `OtlpWriter` for traces)

## Goal

Make the OTLP profile uploader a first-class citizen of the tracer's OTLP export infrastructure, following the exact same patterns as logs and metrics.

---

## Plan

### Step 1: Add OTLP Profiles config to `OtlpConfig` and `Config`

**Files:** `dd-trace-api/.../config/OtlpConfig.java`, `internal-api/.../api/Config.java`, `dd-trace-api/.../api/ConfigDefaults.java`

Add profile-specific OTLP config keys to `OtlpConfig`, mirroring the existing logs/metrics/traces pattern:

```java
// OtlpConfig.java
public static final String OTLP_PROFILES_ENDPOINT = "otlp.profiles.endpoint";
public static final String OTLP_PROFILES_HEADERS = "otlp.profiles.headers";
public static final String OTLP_PROFILES_PROTOCOL = "otlp.profiles.protocol";
public static final String OTLP_PROFILES_COMPRESSION = "otlp.profiles.compression";
public static final String OTLP_PROFILES_TIMEOUT = "otlp.profiles.timeout";
```

Add defaults to `ConfigDefaults`:
```java
static final String DEFAULT_OTLP_HTTP_PROFILES_ENDPOINT = "v1/profiles";
// Provisional — the OTLP profiles proto is still in v1development; update when stabilized
public static final String DEFAULT_OTLP_GRPC_PROFILES_ENDPOINT =
    "opentelemetry.proto.collector.profiles.v1.ProfilesService/Export";
```

Add getters to `Config`, following the same initialization pattern as `otlpLogs*` (endpoint derivation from `agentHost`, protocol-based port selection, headers map, compression enum, timeout).

**Deprecate** (do not remove yet) the ad-hoc keys from `ProfilingConfig`:
- `PROFILING_OTLP_URL` → deprecated, mapped to `OTLP_PROFILES_ENDPOINT` with a warning log if set
- `PROFILING_OTLP_COMPRESSION_ENABLED` → deprecated, mapped to `OTLP_PROFILES_COMPRESSION` (boolean true → `GZIP`, false → `NONE`) with a warning log
- `PROFILING_OTLP_ENABLED` → keep as-is (controls whether the uploader is created)
- `PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD` → keep as-is (converter-specific, not transport)

The deprecated keys should be read as fallbacks during a deprecation period (at least one major release), after which they can be removed.

### Step 2: Create `OtlpProfilesSenderFactory`

**File:** `dd-java-agent/agent-profiling/profiling-uploader/src/main/java/com/datadog/profiling/uploader/OtlpProfilesSenderFactory.java`

Mirror `OtlpMetricsSenderFactory`:

```java
final class OtlpProfilesSenderFactory {
  static OtlpSender create(Config config) {
    switch (config.getOtlpProfilesProtocol()) {
      case GRPC:
        return new OtlpGrpcSender(
            config.getOtlpProfilesEndpoint(),
            "/" + DEFAULT_OTLP_GRPC_PROFILES_ENDPOINT,
            config.getOtlpProfilesHeaders(),
            config.getOtlpProfilesTimeout(),
            config.getOtlpProfilesCompression());
      case HTTP_PROTOBUF:
        return new OtlpHttpSender(
            config.getOtlpProfilesEndpoint(),
            "/v1/profiles",
            config.getOtlpProfilesHeaders(),
            config.getOtlpProfilesTimeout(),
            config.getOtlpProfilesCompression());
      case HTTP_JSON:
        // Profiles are always protobuf; HTTP_JSON uses the same transport as HTTP_PROTOBUF.
        log.warn("OTLP profiles do not support JSON encoding; using HTTP_PROTOBUF transport");
        return new OtlpHttpSender(
            config.getOtlpProfilesEndpoint(),
            "/v1/profiles",
            config.getOtlpProfilesHeaders(),
            config.getOtlpProfilesTimeout(),
            config.getOtlpProfilesCompression());
      default:
        return null;
    }
  }
}
```

**Note:** The gRPC service name `opentelemetry.proto.collector.profiles.v1.ProfilesService/Export` is provisional — the OTLP profiles proto is still in `v1development`. Update when the proto stabilizes.

**Dependency:** `profiling-uploader` must depend on `dd-trace-core` (or extract `otlp/common` to a shared module — see Step 6).

### Step 3: Rewrite `OtlpProfileUploader` to use `OtlpSender`

**File:** `dd-java-agent/agent-profiling/profiling-uploader/.../OtlpProfileUploader.java`

Replace the hand-rolled OkHttp client, request builder, gzip, and retry logic with:

```java
public final class OtlpProfileUploader implements RecordingDataListener {

  private final OtlpSender sender;
  // No shared converter — created fresh per call for thread safety
  private final boolean includeOriginalPayload;

  // ... constructor uses OtlpProfilesSenderFactory.create(config) ...

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean sync) {
    try {
      byte[] otlpBytes = convertToOtlp(data); // fresh JfrToOtlpConverter per call
      OtlpPayload payload = new OtlpPayload(
          ByteBuffer.wrap(otlpBytes), OtlpPayload.PROTOBUF_CONTENT_TYPE);
      if (sync) {
        sender.send(payload); // blocking
      } else {
        executor.execute(() -> {
          try {
            sender.send(payload);
          } catch (Exception e) {
            log.warn("OTLP profile upload failed", e);
          }
        });
      }
    } catch (Exception e) {
      log.warn("OTLP profile conversion failed", e);
    } finally {
      // Safe: conversion is synchronous, payload is a self-contained byte[] copy
      data.release();
    }
  }

  public void shutdown() {
    executor.shutdown();
    sender.shutdown();
  }
}
```

This eliminates:
- Custom `OkHttpClient` construction
- Custom `Request.Builder` / headers / gzip
- Custom `handleResponse` / `handleFailure`
- Custom connection pool eviction (delegated to `OtlpSender.shutdown()`)

What remains: conversion (JFR→OTLP) + lifecycle (retain/release, executor for async).

### Step 4: Add async dispatch

`OtlpSender.send()` is synchronous (blocking). The existing `OtlpProfileUploader` supports both sync (shutdown) and async (periodic) uploads. Keep a small `ExecutorService` for async dispatch, same as the current implementation:

```java
private final ExecutorService executor =
    new ThreadPoolExecutor(0, MAX_RUNNING_REQUESTS, 60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        new AgentThreadFactory(AgentThread.PROFILER_HTTP_DISPATCHER),
        new ThreadPoolExecutor.AbortPolicy());
```

**Rejected execution handling:** The `SynchronousQueue` + `AbortPolicy` throws `RejectedExecutionException` when all threads are busy. The `onNewData` async path must catch this and log a warning (the data is already released in the `finally` block, so no leak):

```java
try {
  executor.execute(() -> { ... });
} catch (RejectedExecutionException e) {
  log.warn("OTLP profile upload rejected: too many concurrent requests");
}
```

The `OtlpMetricsService` and `OtlpLogsService` use a dedicated exporter thread with a sleep loop. Profiles are different — they're pushed by `ProfilingSystem.snapshot()`, not polled. So the executor pattern is correct for profiles.

### Step 5: Add telemetry

**File:** `internal-api/.../api/telemetry/OtlpTelemetry.java`

Add a `profilesExport` counter set, mirroring `tracesExport` and `metricsExport`:

```java
private final String[] profilesTags = tagsFor(Config.get().getOtlpProfilesProtocol());
private final ExportCounters profilesExport = new ExportCounters("profiles");

public void onProfilesExportAttempt() { profilesExport.attempts.increment(); }
public void onProfilesExportComplete(boolean success) { profilesExport.complete(success); }
```

Wire calls in `OtlpProfileUploader` around `sender.send()` (import `datadog.trace.common.writer.RemoteApi` or the extracted `OtlpResponse`):
```java
OtlpTelemetry.getInstance().onProfilesExportAttempt();
RemoteApi.Response response = sender.send(payload);
OtlpTelemetry.getInstance().onProfilesExportComplete(response.success());
```

### Step 6: Resolve the module dependency

**Problem:** `OtlpSender`, `OtlpGrpcSender`, `OtlpHttpSender`, `OtlpPayload`, `OtlpSenderSupport` live in `dd-trace-core/.../otlp/common/`. `profiling-uploader` currently does not depend on `dd-trace-core`.

**Options:**

1. **Add `dd-trace-core` as a dependency of `profiling-uploader`** — simplest, but `dd-trace-core` is a large module and this creates a heavy dependency for a small uploader.

2. **Extract `otlp/common` to a shared module** (e.g. `communication/otlp-common` or `utils/otlp-common`) — cleaner, but requires moving classes and updating all existing consumers.

3. **Move `OtlpProfileUploader` to `dd-trace-core`** — it would naturally live alongside the other OTLP services. But it implements `RecordingDataListener` (from `internal-api`) and needs `JfrToOtlpConverter` (from `profiling-otel`), creating a reverse dependency.

**Recommendation:** Option 2 (extract `otlp/common` sender infrastructure to a lightweight shared module). This is the architecturally correct choice and keeps dependencies clean.

**New module:** `communication/otlp-exporter`
- `OtlpSender` interface
- `OtlpGrpcSender`, `OtlpHttpSender`
- `OtlpPayload`, `OtlpHttpRequestBody`, `OtlpGrpcRequestBody`
- `OtlpSenderSupport`
- `OtlpTraceFlags` (shared utility)

**Stays in `dd-trace-core`** (used by collectors, not senders):
- `OtlpProtoBuffer`, `OtlpCommonProto` (used by trace/metrics/logs collectors)
- `OtlpResourceAttributes`, `OtlpResourceJson`, `OtlpResourceProto`
- All per-signal collectors (`OtlpTraceProtoCollector`, `OtlpMetricsProtoCollector`, etc.)

**The `RemoteApi` problem:** `OtlpSender.send()` returns `RemoteApi.Response`, but `RemoteApi` lives in `dd-trace-core/.../common/writer/`, not `dd-trace-api`. Extracting the senders without addressing this would still require a `dd-trace-core` dependency, defeating the purpose. **Fix:** extract `RemoteApi.Response` (a simple value class with `success()`/`failed()` factory methods and a `boolean success()` getter) to `dd-trace-api` or into the new module as `OtlpResponse`. Update `OtlpSender` to return `OtlpResponse` instead. This is a small, self-contained class with no heavy dependencies.

Dependencies of new module: `communication/http` (for `OkHttpUtils`, `HttpRetryPolicy`), `dd-trace-api` (for `OtlpConfig`), `utils/logging-utils` (for `RatelimitedLogger`).

Consumers: `dd-trace-core` (traces, metrics, logs), `profiling-uploader` (profiles).

**Affected build files:** `settings.gradle.kts` (new module), `dd-trace-core/build.gradle` (depend on new module), `dd-java-agent/agent-profiling/profiling-uploader/build.gradle` (depend on new module).

### Step 7: Update `ProfilingAgent` wiring

**File:** `dd-java-agent/agent-profiling/.../ProfilingAgent.java`

The current wiring in `ProfilingAgent.run()` creates `OtlpProfileUploader` and wraps it in a lambda that calls `retain()` + `otlp.upload()` + `downstream.onNewData()`. This stays the same — the `OtlpProfileUploader` still implements `RecordingDataListener` and the retain/release protocol is unchanged. Only the internals of `OtlpProfileUploader` change (delegating to `OtlpSender` instead of hand-rolled HTTP).

### Step 8: Migrate config consumers

Update `OtlpProfileUploader` constructor to read from the new `Config.getOtlpProfiles*()` getters instead of `ConfigProvider.getBoolean(PROFILING_OTLP_*)`.

Update tests:
- `OtlpProfileUploaderTest` — mock `Config.getOtlpProfiles*()` instead of `ConfigProvider.getBoolean(PROFILING_OTLP_*)`
- Add tests for gRPC protocol path (currently only HTTP is tested)

### Step 9: Update `settings.gradle.kts`

Add the new `communication/otlp-exporter` module (if Option 2) and wire it into the `profiling-uploader` and `dd-trace-core` dependency graphs.

---

## Summary of Changes

| Step | Files | Effort | Risk |
|------|-------|--------|------|
| 1: Config keys | `OtlpConfig`, `Config`, `ConfigDefaults` | Small | Low — additive |
| 2: Sender factory | New `OtlpProfilesSenderFactory` | Small | Low — mirrors existing |
| 3: Rewrite uploader | `OtlpProfileUploader` | Medium | Medium — core rewrite |
| 4: Async dispatch | `OtlpProfileUploader` | Small | Low |
| 5: Telemetry | `OtlpTelemetry` | Small | Low — additive |
| 6: Extract module | New `communication/otlp-exporter`, `dd-trace-core/build.gradle` | Medium | Medium — touches all OTLP signals, extract `RemoteApi.Response` |
| 7: ProfilingAgent | `ProfilingAgent` | Trivial | Low — no change to wiring |
| 8: Migrate config | `OtlpProfileUploader`, tests | Small | Low |
| 9: Settings | `settings.gradle.kts`, `dd-trace-core/build.gradle` | Trivial | Low |

**Recommended order:** 6 → 1 → 2 → 3 → 4 → 5 → 8 → 7 → 9

Step 6 first because it unblocks everything else. Steps 1-2 are additive and safe. Step 3 is the main rewrite.

## What Gets Eliminated

- Custom `OkHttpClient` construction in `OtlpProfileUploader` (~40 lines)
- Custom `Request.Builder` / headers / gzip (~30 lines)
- Custom `handleResponse` / `handleFailure` / `uploadSync` / `uploadAsync` (~60 lines)
- Custom `compress()` method (~15 lines)
- Custom connection pool eviction
- Ad-hoc `profiling.otlp.url` / `profiling.otlp.compression.enabled` config keys
- Duplicate retry/error handling logic

**Net: ~130 lines removed from `OtlpProfileUploader` (~150 removed, ~20 added for factory), replaced by ~10 lines of `OtlpSender` delegation.**

## What Stays

- `JfrToOtlpConverter` (the JFR→OTLP conversion — this is profile-specific, not transport)
- `RecordingDataListener` implementation + retain/release protocol
- `ProfilingAgent` wiring (lambda wrapping OTLP + downstream JFR uploader)
- `PROFILING_OTLP_ENABLED` and `PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD` config keys (profile-specific, not transport)
