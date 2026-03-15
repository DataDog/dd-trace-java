# PROF-10633 — Unified Profiling Configuration

## 2026-02-26 — Implementation complete, review passed

### Status: COMPLETE — all GA features default to true

## 2026-02-26 — Enable all GA features by default

### User feedback
> All GA features are to be enabled by default.

### Changes made

**ProfilingConfig.java**: Added 5 `_DEFAULT = true` constants and updated Javadoc.

**DatadogProfilerConfig.java**:
- `isCpuProfilerEnabled`: Uses `PROFILING_CPU_ENABLED_DEFAULT` (no behavior change).
- `isMemoryLeakProfilingEnabled`: Uses `PROFILING_LIVEHEAP_ENABLED_DEFAULT` (true) instead
  of `PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED_DEFAULT` (false). **BEHAVIOR CHANGE.**

**OpenJdkController.java**: Liveheap uses default=true, triggers "pick best backend" logic.
Added `isOldObjectSampleAvailable()` guard for default-only path.

**ProfilerFlareReporter.java**: Umbrella section shows `true` defaults instead of null.

**DatadogProfilerConfigTest.java**: Liveheap default assertion changed to `assertTrue`.

### All tests pass after spotlessApply

### Files changed
- `dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java`
- `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java`
- `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/main/java/com/datadog/profiling/controller/openjdk/OpenJdkController.java`
- `dd-java-agent/agent-profiling/profiling-controller-openjdk/src/test/java/com/datadog/profiling/controller/openjdk/OpenJdkControllerTest.java`
- `dd-java-agent/agent-profiling/profiling-ddprof/src/test/java/com/datadog/profiling/ddprof/DatadogProfilerConfigTest.java`
- `dd-java-agent/agent-profiling/profiling-controller/src/main/java/com/datadog/profiling/controller/ProfilerFlareReporter.java`
