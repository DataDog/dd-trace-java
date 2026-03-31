# Tracer Flare File: Data Source Reference

## What's Actually in Current Java Tracer Flare

Based on the source code analysis:

| Data Field | In Current Flare? | Source Code | How to Get It |
|------------|------------------|-------------|---------------|
| **METADATA** |
| `tracer_version` | ✅ YES | `TracerFlareService.java:218` | `VersionInfo.VERSION` |
| `service_name` | ✅ YES (in config) | `initial_config.txt` | `Config.get().getServiceName()` |
| `environment` | ✅ YES (in config) | `initial_config.txt` | `Config.get().getEnv()` |
| `hostname` | ✅ YES (in config) | `initial_config.txt` | `Config.get().getHostName()` |
| `runtime_id` | ✅ YES (in config) | `initial_config.txt` | `Config.get().getRuntimeId()` |
| **RUNTIME** |
| `jvm_args` | ✅ YES | `TracerFlareService.java:228` | `RuntimeMXBean.getInputArguments()` |
| `classpath` | ✅ YES | `TracerFlareService.java:229` | `RuntimeMXBean.getClassPath()` |
| `library_path` | ✅ YES | `TracerFlareService.java:230` | `RuntimeMXBean.getLibraryPath()` |
| `boot_classpath` | ⚠️ OPTIONAL | `TracerFlareService.java:232` | `RuntimeMXBean.getBootClassPath()` if supported |
| `jvm_version` | ❌ NO | - | Would need: `System.getProperty("java.version")` |
| `jvm_vendor` | ❌ NO | - | Would need: `System.getProperty("java.vendor")` |
| `os_name` | ❌ NO | - | Would need: `System.getProperty("os.name")` |
| `os_version` | ❌ NO | - | Would need: `System.getProperty("os.version")` |
| `os_arch` | ❌ NO | - | Would need: `System.getProperty("os.arch")` |
| `cpu_cores` | ❌ NO | - | Would need: `Runtime.getRuntime().availableProcessors()` |
| `memory_total_gb` | ❌ NO | - | Would need: `Runtime.getRuntime().maxMemory()` |
| `heap_max_mb` | ❌ NO | - | Would need: Parse from `-Xmx` or `Runtime.getRuntime().maxMemory()` |
| `heap_init_mb` | ❌ NO | - | Would need: Parse from `-Xms` or `MemoryMXBean.getHeapMemoryUsage()` |
| **DEPENDENCIES** |
| Framework versions | ❌ NO | - | Not captured (need to add DependencyExtractor) |
| **INSTRUMENTATION** |
| Instrumentation names | ✅ YES | `InstrumenterState.java:172` | `InstrumenterState.summary()` |
| Framework versions | ❌ NO | - | Not captured (just names, no versions) |
| **HEALTH METRICS** |
| Counters (totals) | ✅ YES | `CoreTracer.java:1460` | `healthMetrics.summary()` |
| **LOAD PROFILE** |
| Request rates | ❌ NO | - | Not captured (need to add LoadProfileTracker) |
| Concurrency | ❌ NO | - | Not captured |
| Traffic pattern | ❌ NO | - | Not captured |
| **TRACES** |
| Sample traces | ✅ YES | `PendingTraceBuffer.java:374-383` | Up to 50 pending traces |
| **TRACE STATISTICS** |
| Aggregated stats | ❌ NO | - | Not captured (need to add TraceStatisticsCollector) |
| Slow traces | ❌ NO | - | Not captured |

---

## Summary of What I Made Up vs Reality

### In `tracer_flare_current_state.txt` - CORRECTED ✅

**Removed (not actually captured)**:
- ❌ `jvm_version`
- ❌ `jvm_vendor`
- ❌ `os_name`
- ❌ `os_version`
- ❌ `os_arch`
- ❌ `cpu_cores`
- ❌ `memory_total_gb`

**File now contains ONLY**:
- ✅ `jvm_args` (actually captured)
- ✅ `classpath` (actually captured)
- ✅ Instrumentation names (actually captured)
- ✅ Health counters (actually captured)
- ✅ Sample traces (actually captured)

### In `tracer_flare_ideal_state.txt` - KEPT ✅

**Kept (these SHOULD be captured)**:
- ✅ `jvm_version` - Easy to add via `System.getProperty("java.version")`
- ✅ `jvm_vendor` - Easy to add via `System.getProperty("java.vendor")`
- ✅ `os_name` - Easy to add via `System.getProperty("os.name")`
- ✅ `os_version` - Easy to add via `System.getProperty("os.version")`
- ✅ `os_arch` - Easy to add via `System.getProperty("os.arch")`
- ✅ `cpu_cores` - Easy to add via `Runtime.getRuntime().availableProcessors()`
- ✅ `memory_total_gb` - Easy to add via `Runtime.getRuntime().maxMemory()`
- ✅ All the new sections (dependencies, load profile, trace stats)

---

## How to Actually Add System Info to Flare

If you want to add these fields, here's the code:

```java
// In TracerFlareService.java, add new method:

private void addSystemInfo(ZipOutputStream zip) throws IOException {
  StringBuilder info = new StringBuilder();
  
  // JVM Info
  info.append("jvm.version=").append(System.getProperty("java.version")).append('\n');
  info.append("jvm.vendor=").append(System.getProperty("java.vendor")).append('\n');
  info.append("jvm.vendor.version=").append(System.getProperty("java.vendor.version")).append('\n');
  
  // OS Info
  info.append("os.name=").append(System.getProperty("os.name")).append('\n');
  info.append("os.version=").append(System.getProperty("os.version")).append('\n');
  info.append("os.arch=").append(System.getProperty("os.arch")).append('\n');
  
  // Hardware Info
  Runtime runtime = Runtime.getRuntime();
  info.append("cpu.cores=").append(runtime.availableProcessors()).append('\n');
  info.append("memory.max.bytes=").append(runtime.maxMemory()).append('\n');
  info.append("memory.total.bytes=").append(runtime.totalMemory()).append('\n');
  info.append("memory.free.bytes=").append(runtime.freeMemory()).append('\n');
  
  TracerFlare.addText(zip, "system_info.txt", info.toString());
}

// Then call it in buildFlareZip():
private byte[] buildFlareZip(long startMillis, long endMillis, boolean dumpThreads) {
  // ...
  addPrelude(zip, startMillis, endMillis);
  addConfig(zip);
  addRuntime(zip);
  addSystemInfo(zip);  // ← ADD THIS
  // ...
}
```

---

## Current State File: What's Really There

```
ACTUALLY CAPTURED:
✅ tracer_version (from VersionInfo.VERSION)
✅ jvm_args (from RuntimeMXBean)
✅ classpath (from RuntimeMXBean)
✅ library_path (from RuntimeMXBean)
✅ Instrumentation names (from InstrumenterState)
✅ Health counters (from healthMetrics)
✅ 50 pending traces (from PendingTraceBuffer)
✅ Thread dump (if enabled)

NOT CAPTURED (but should be):
❌ JVM version/vendor
❌ OS name/version/arch
❌ CPU cores
❌ Memory size
❌ Dependencies
❌ Load profile
❌ Trace statistics
```

---

## Ideal State File: What Should Be There

```
WOULD BE CAPTURED (with enhancements):
✅ Everything from current state
✅ System info (JVM version, OS, CPU, memory) ← Easy to add
✅ Dependencies ← Requires new DependencyExtractor
✅ Load profile ← Requires new LoadProfileTracker
✅ Trace statistics ← Requires new TraceStatisticsCollector
✅ Slow traces ← Part of TraceStatisticsCollector
```

---

## 🎯 Bottom Line

**Current State File**: Now shows ONLY data that actually exists in flares today  
**Ideal State File**: Shows what SHOULD exist (including easy additions like CPU cores)

**Easy to add** (5 minutes):
- CPU cores via `Runtime.getRuntime().availableProcessors()`
- Memory via `Runtime.getRuntime().maxMemory()`
- JVM/OS info via `System.getProperty()`

**Requires implementation** (1 week each):
- Dependencies
- Load profile
- Trace statistics

