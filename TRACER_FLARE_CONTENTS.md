# Tracer Flare Contents

A tracer flare is a diagnostic ZIP file that collects comprehensive information about the state of the dd-trace-java tracer. This document describes all the files included in the flare and where they are generated from in the codebase.

## Core Architecture

The tracer flare system uses a **Reporter pattern** where different components of the tracer can register themselves to contribute information to flares.

| Component | Class | Role |
|-----------|-------|------|
| Main Service | [`TracerFlareService.java`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java) | Orchestrates flare creation and upload |
| Reporter Interface | [`TracerFlare.java`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/main/java/datadog/trace/api/flare/TracerFlare.java) | Defines the contract for contributing to flares |
| Flare Poller | [`TracerFlarePoller.java`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlarePoller.java) | Polls the agent for flare requests |

## Files Included in the Flare

### 1. Flare Metadata

| File Name | Contains | Source | Additional Details |
|-----------|----------|--------|-------------------|
| `flare_info.txt` | Timestamps for when the flare was requested and completed | [`TracerFlareService.java:217-218`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L217-L218) | Format: `Requested: <ISO 8601 timestamp>` / `Completed: <ISO 8601 timestamp>` |
| `tracer_version.txt` | Version of the dd-trace-java tracer | [`TracerFlareService.java:218`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L218) | Semantic version string (e.g., "1.2.3") |

---

### 2. Configuration Information

| File Name | Contains | Reporter | Source | Additional Details |
|-----------|----------|----------|--------|-------------------|
| `initial_config.txt` | Complete tracer configuration including all settings and their values | TracerFlareService | [`TracerFlareService.java:222`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L222) | Full `Config.toString()` output with all tracer configuration options |
| `dynamic_config.txt` | Runtime configuration changes made via remote config or dynamic updates | CoreTracer | [`CoreTracer.java:1459`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java#L1459) | Shows configuration that has been dynamically modified during runtime |

---

### 3. Runtime Environment

| File Name | Contains | Source | Additional Details |
|-----------|----------|--------|-------------------|
| `jvm_args.txt` | JVM command-line arguments | [`TracerFlareService.java:228`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L228) | Space-separated JVM arguments (e.g., `-Xmx1g -Xms512m`) |
| `classpath.txt` | Java classpath at runtime | [`TracerFlareService.java:229`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L229) | Full classpath including all JARs and directories |
| `library_path.txt` | Java library path (native libraries) | [`TracerFlareService.java:230`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L230) | Platform-specific library paths |
| `boot_classpath.txt` | Bootstrap classpath (optional) | [`TracerFlareService.java:232`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L232) | Only included if `RuntimeMXBean.isBootClassPathSupported()` returns true |

---

### 4. Thread Information

| File Name | Contains | Source | Additional Details |
|-----------|----------|--------|-------------------|
| `threads.txt` | Complete thread dump of all JVM threads (optional) | [`TracerFlareService.java:251-265`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L251-L265) | Only included when `dumpThreads=true` (enabled in triage mode or debug logging). Contains full thread stack traces with monitor and synchronizer information |

---

### 5. Logging

| File Name | Contains | Reporter | Source | Size Limit | Additional Details |
|-----------|----------|----------|--------|-----------|-------------------|
| `tracer.log` (or split files) | Tracer log output | LogReporter | [`LogReporter.java:63-103`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-logging/src/main/java/datadog/trace/logging/LogReporter.java#L63-L103) | 15 MB max | If ≤15MB: single file. If >15MB: split into `tracer_begin.log` (first 7.5MB) and `tracer_end.log` (last 7.5MB). If no configured log: captures during flare preparation |

---

### 6. Profiling Information

| File Name | Contains | Reporter | Source | Size/Condition | Additional Details |
|-----------|----------|----------|--------|---------------|-------------------|
| `profiler_config.txt` | Comprehensive profiler configuration and initialization status | ProfilerFlareReporter | [`ProfilerFlareReporter.java:29-529`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerFlareReporter.java#L29-L529) | - | Includes: initialization status, core settings, upload settings, proxy settings, allocation/heap/exception/backpressure profiling, JFR settings, DDProf settings (CPU, wall, allocation, live heap), scheduling, context & timeline, queueing time, SMAP, and misc settings |
| `profiling_template_override.jfp` | Custom JFR template override file content | ProfilerFlareReporter | [`ProfilerFlareReporter.java:30-41`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerFlareReporter.java#L30-L41) | Optional | Only if `DD_PROFILING_TEMPLATE_OVERRIDE_FILE` is configured and file exists |
| `profiler_env.txt` | Profiler environment validation results | ProfilerFlareReporter | [`ProfilerFlareReporter.java:43-47`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerFlareReporter.java#L43-L47) | - | Environment checks for profiler operation (temp directory access, permissions, etc.) |
| `profiler_log.txt` | Profiler-specific log messages | ProfilerFlareLogger | [`ProfilerFlareLogger.java:59-64`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/main/java/datadog/trace/api/profiling/ProfilerFlareLogger.java#L59-L64) | 2 MB max | Profiler-specific logs captured during the flare preparation window |

---

### 7. Trace Information

| File Name | Contains | Reporter | Source | Limit | Additional Details |
|-----------|----------|----------|--------|-------|-------------------|
| `traces.json` | Sample of pending traces in the trace buffer | TracerDump | [`PendingTraceBuffer.java:374-383`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/PendingTraceBuffer.java#L374-L383) | 50 traces | JSON array of traces with full span details. Captured traces waiting to be sent to the agent, sorted by start time |

---

### 8. Metrics and Health

| File Name | Contains | Reporter | Source | Additional Details |
|-----------|----------|----------|--------|-------------------|
| `tracer_health.txt` | Tracer health metrics and statistics | CoreTracer | [`CoreTracer.java:1460`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java#L1460) | Health metrics including trace buffer status, dropped traces, and other operational metrics |
| `span_metrics.txt` | Span-level metrics and statistics | CoreTracer | [`CoreTracer.java:1461`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java#L1461) | Metrics about spans created, sampled, and processed |

---

### 9. Instrumentation Information

| File Name | Contains | Reporter | Source | Additional Details |
|-----------|----------|----------|--------|-------------------|
| `instrumenter_state.txt` | State of the bytecode instrumentation system | InstrumenterFlare | [`InstrumenterFlare.java:16`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterFlare.java#L16) | Current state of all active instrumentations |
| `instrumenter_metrics.txt` | Instrumentation performance metrics | InstrumenterFlare | [`InstrumenterFlare.java:17`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterFlare.java#L17) | Metrics about instrumentation performance and behavior |

---

### 10. Dynamic Instrumentation (Debugger)

| File Name | Contains | Reporter | Source | Additional Details |
|-----------|----------|----------|--------|-------------------|
| `dynamic_instrumentation.txt` | State of Dynamic Instrumentation / Live Debugging | DebuggerAgent | [`DebuggerAgent.java:494-540`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java#L494-L540) | Includes: Snapshot upload URL, Diagnostic upload URL, Symbol database URL, Probe definitions, Instrumented probes, Probe statuses, Symbol database statistics, Exception fingerprints, Source file tracking entries count |

---

### 11. JMX Metrics

| File Name | Contains | Reporter | Source | Additional Details |
|-----------|----------|----------|--------|-------------------|
| `jmxfetch.txt` | JMXFetch metrics collected during flare preparation | AgentStatsdReporter | [`AgentStatsdReporter.java:81-91`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/AgentStatsdReporter.java#L81-L91) | Key-value pairs of JMX metrics recorded during the flare preparation window (10-20 minutes) |

---

### 12. Error Information

| File Name | Contains | Source | Additional Details |
|-----------|----------|--------|-------------------|
| `flare_errors.txt` | Errors that occurred while collecting flare information (only if errors occur) | [`TracerFlare.java:48-54`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/main/java/datadog/trace/api/flare/TracerFlare.java#L48-L54) | Lists any exceptions thrown by reporters during flare collection. Ensures flare collection continues even if individual reporters fail |

---

## Flare Lifecycle

| Phase | Step | Action | Source | Details |
|-------|------|--------|--------|---------|
| **Preparation** (10-20 min before) | 1 | Log Level Override (optional) | [`TracerFlareService.java:106-123`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L106-L123) | Debug logging can be enabled |
| **Preparation** | 2 | Reporter Preparation | [`TracerFlare.java:25-32`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/main/java/datadog/trace/api/flare/TracerFlare.java#L25-L32) | Each reporter's `prepareForFlare()` called. LogReporter starts capturing logs, JMXFetch starts recording metrics, PendingTraceBuffer captures trace samples |
| **Collection** | 3 | Report Building | [`TracerFlareService.java:197-213`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L197-L213) | Flare ZIP is constructed with all collected data |
| **Collection** | 4 | Upload | [`TracerFlareService.java:156-191`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L156-L191) | Flare sent to Datadog Agent at endpoint: `http://<agent-host>:<agent-port>/tracer_flare/v1` |
| **Cleanup** | 5 | Reporter Cleanup | [`TracerFlare.java:57-64`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/main/java/datadog/trace/api/flare/TracerFlare.java#L57-L64) | Each reporter's `cleanupAfterFlare()` called. LogReporter stops capturing and deletes temp files, Log level restored, JMXFetch stops recording metrics |

---

## Configuration

### Triggering a Flare

| Method | Type | Description | Source | Configuration |
|--------|------|-------------|--------|---------------|
| Remote Request | Recommended | Agent polls for flare requests and notifies tracer | [`TracerFlarePoller.java`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlarePoller.java) | Triggered via Datadog Agent |
| Automatic Triage Report | For debugging | Automatically generates a flare after the specified delay and saves to disk | [`TracerFlareService.java:71-104`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L71-L104) | Set `DD_TRIAGE_REPORT_TRIGGER=<delay>` (e.g., "60s", "10m")<br>Saves to `DD_TRIAGE_REPORT_DIR` (default: ".") |

### Configuration Options

| Configuration Variable | Description | Example/Default |
|----------------------|-------------|-----------------|
| `DD_TRIAGE_REPORT_TRIGGER` | Delay before auto-generating triage report | e.g., "5m", "30s" |
| `DD_TRIAGE_REPORT_DIR` | Directory to save triage reports | default: "." |
| `DD_TRIAGE_ENABLED` | Include thread dumps in flares | default: false |

### Size Limits

| Component | Limit | Details |
|-----------|-------|---------|
| Log files | 15 MB max | 7.5 MB from beginning + 7.5 MB from end if exceeded |
| Profiler logs | 2 MB max | Captured during flare preparation window |
| Trace samples | 50 traces max | Oldest traces from the buffer |
| Total flare size | No hard limit | Typically 1-20 MB |

---

## Registered Reporters

The following components register themselves as flare reporters during tracer initialization:

| Reporter | Class | Registration |
|----------|-------|-------------|
| Tracer Core | `CoreTracer` | [`CoreTracer.java:660`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java#L660) |
| Logging | `LogReporter` | [`LogReporter.java:38`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-logging/src/main/java/datadog/trace/logging/LogReporter.java#L38) |
| Trace Buffer | `PendingTraceBuffer.TracerDump` | [`PendingTraceBuffer.java:95`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/PendingTraceBuffer.java#L95) |
| Profiler Config | `ProfilerFlareReporter` | [`ProfilerFlareReporter.java:20`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerFlareReporter.java#L20) |
| Profiler Logs | `ProfilerFlareLogger` | [`ProfilerFlareLogger.java:24`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/main/java/datadog/trace/api/profiling/ProfilerFlareLogger.java#L24) |
| Instrumentation | `InstrumenterFlare` | [`InstrumenterFlare.java:11`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterFlare.java#L11) |
| Dynamic Instrumentation | `DebuggerAgent` | [`DebuggerAgent.java:105`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-debugger/src/main/java/com/datadog/debugger/agent/DebuggerAgent.java#L105) |
| JMXFetch | `AgentStatsdReporter` | [`JMXFetch.java:96`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-jmxfetch/src/main/java/datadog/trace/agent/jmxfetch/JMXFetch.java#L96) |

---

## Testing

| Test Type | Test File | Purpose |
|-----------|-----------|---------|
| Smoke Tests | [`TracerFlareSmokeTest.groovy`](https://github.com/DataDog/dd-trace-java/blob/master/dd-smoke-tests/tracer-flare/src/test/groovy/datadog/smoketest/TracerFlareSmokeTest.groovy) | End-to-end testing of tracer flare functionality |
| Unit Tests | [`TracerFlareTest.groovy`](https://github.com/DataDog/dd-trace-java/blob/master/internal-api/src/test/groovy/datadog/trace/api/flare/TracerFlareTest.groovy) | Unit testing of TracerFlare API |

---

## Summary

A tracer flare provides a comprehensive snapshot of the tracer's state:

| Category | What's Included | Use Case |
|----------|-----------------|----------|
| ✅ Configuration | Initial and dynamic config | Verify tracer settings and remote config changes |
| ✅ Runtime Environment | JVM args, classpath, library paths, thread dumps | Diagnose environment and threading issues |
| ✅ Logs | Tracer and profiler logs | Analyze application behavior and errors |
| ✅ Profiler | Configuration, environment checks, logs | Troubleshoot profiling issues |
| ✅ Traces | Sample of pending traces | Inspect trace structure and content |
| ✅ Metrics & Health | Tracer health, span metrics | Monitor tracer performance |
| ✅ Instrumentation | State and performance metrics | Debug instrumentation issues |
| ✅ Dynamic Instrumentation | Debugger state, probes, snapshots | Troubleshoot live debugging |
| ✅ JMX | JMXFetch metrics | Validate JMX metric collection |

This information is invaluable for diagnosing issues, troubleshooting performance problems, and understanding the tracer's behavior in production environments.

