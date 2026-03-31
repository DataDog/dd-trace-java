# Java Tracer Flare: Status Check

## ✅ What the Java Tracer Flare HAS

### 1. ✅ Version of the Tracer - **YES**

**File**: `tracer_version.txt`  
**Source**: [`TracerFlareService.java:218`](https://github.com/DataDog/dd-trace-java/blob/master/utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java#L218)  
**Content**: Tracer version string (e.g., "1.41.0")

```java
TracerFlare.addText(zip, "tracer_version.txt", VERSION);
```

The version comes from [`VersionInfo.VERSION`](https://github.com/DataDog/dd-trace-java/blob/master/utils/version-utils/src/main/java/datadog/common/version/VersionInfo.java) which is populated at build time.

---

### 2. ⚠️ Version of Instrumented Frameworks - **PARTIAL**

**File**: `instrumenter_state.txt`  
**Source**: [`InstrumenterFlare.java:16`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterFlare.java#L16)  
**Content**: Which instrumentations were applied/blocked per classloader

```java
TracerFlare.addText(zip, "instrumenter_state.txt", InstrumenterState.summary());
```

#### What it Contains:
- ✅ **Instrumentation names** (e.g., "spring-webmvc", "jdbc", "okhttp")
- ✅ **Instrumentation class names**
- ✅ **Status per classloader** (applied or blocked)

#### What it DOESN'T Contain:
- ❌ **Actual framework versions** (e.g., Spring Boot 3.0.1, Hibernate 6.2.0)
- ❌ **Dependency tree**
- ❌ **Minimum version matched** (e.g., "spring-webmvc-5.3" means >= 5.3, but what's the actual version?)

#### Example Output Format:
```
org.springframework.boot.loader.LaunchedURLClassLoader
  instrumentation.names=[spring-webmvc] instrumentation.class=...SpringWebMvcInstrumentation APPLIED
  instrumentation.names=[jdbc] instrumentation.class=...JdbcInstrumentation APPLIED
  instrumentation.names=[hibernate] instrumentation.class=...HibernateInstrumentation BLOCKED
```

**Gap**: You know Spring WebMVC **instrumentation** was applied, but not if it's Spring 5.3.0 or 6.1.0!

---

### 3. ✅ Example Traces - **YES**

**File**: `pending_traces.txt` (not `traces.json`)  
**Source**: [`PendingTraceBuffer.java:374-383`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/PendingTraceBuffer.java#L374-L383)  
**Content**: Up to 50 pending traces in JSON format (one per line)  
**Reporter**: `TracerDump` (nested class in PendingTraceBuffer)

```java
TraceDumpJsonExporter writer = new TraceDumpJsonExporter(zip);
for (Element e : DumpDrain.DUMP_DRAIN.collectTraces()) {
  if (e instanceof PendingTrace) {
    PendingTrace trace = (PendingTrace) e;
    writer.write(trace.getSpans());  // Writes JSON array of spans
  }
}
```

#### What Each Trace Contains:
- ✅ **Trace ID**
- ✅ **Span IDs** (with parent relationships)
- ✅ **Service name**
- ✅ **Resource name** (endpoint/operation)
- ✅ **Operation name**
- ✅ **Start time** (nanoseconds)
- ✅ **Duration** (nanoseconds)
- ✅ **Tags** (all span tags)
- ✅ **Metrics** (numeric values)
- ✅ **Error flag**
- ✅ **Sampling priority**

#### Sampling Strategy:
- Captures up to 50 traces from the pending trace buffer
- **Sorted by start time** (oldest first)
- Includes traces that are:
  - Waiting to be sent to the agent
  - Not yet complete (missing child spans)
  - Being held for partial trace flush

#### Example JSON (simplified):
```json
[
  {
    "trace_id": "1234567890",
    "span_id": "9876543210",
    "parent_id": "0",
    "service": "my-service",
    "resource": "GET /api/users",
    "name": "servlet.request",
    "start": 1638360000000000000,
    "duration": 125000000,
    "error": 0,
    "tags": {
      "http.method": "GET",
      "http.status_code": "200",
      "http.url": "/api/users"
    }
  }
]
```

---

### 4. ❌ Traces Per Second Statistics - **NO**

**Status**: Not included in flare  
**Available Internally**: Yes, via health metrics, but not aggregated or included in flare

#### What Health Metrics ARE Collected:
From [`TracerHealthMetrics.java`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/monitor/TracerHealthMetrics.java):

- ✅ `createdTraces` - Total traces created (counter)
- ✅ `finishedSpans` - Total spans finished (counter)
- ✅ `flushedTraces` - Total traces sent to agent (counter)
- ✅ `enqueuedSpans` - Total spans queued (counter)
- ❌ **Traces per second** (not calculated)
- ❌ **Request rate** (not tracked)
- ❌ **Concurrency level** (not tracked)

#### What IS in the Flare:

**File**: `tracer_health.txt`  
**Source**: [`CoreTracer.java:1460`](https://github.com/DataDog/dd-trace-java/blob/master/dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java#L1460)  
**Content**: Summary of health metrics

```java
TracerFlare.addText(zip, "tracer_health.txt", healthMetrics.summary());
```

Likely contains counters like:
- Total traces created
- Total spans finished
- Queue depths
- Dropped traces
- But **NOT rates or per-second stats**

#### Gap for Performance Reproduction:

Without rate information, you can't answer:
- What was the request rate when the issue occurred?
- How many concurrent requests were active?
- What was the trace throughput (traces/sec)?

**This is critical for reproducing load conditions!**

---

## 📊 Summary Table

| # | Component | Status | File Name | Notes |
|---|-----------|--------|-----------|-------|
| 1 | **Tracer Version** | ✅ YES | `tracer_version.txt` | Exact tracer version (e.g., "1.41.0") |
| 2 | **Framework Versions** | ⚠️ PARTIAL | `instrumenter_state.txt` | Has instrumentation names, NOT actual framework versions |
| 3 | **Example Traces** | ✅ YES | `pending_traces.txt` | Up to 50 traces with full span details (JSON) |
| 4 | **Traces/sec Stats** | ❌ NO | - | No rate calculations, no load profile |

---

## 🚨 Critical Gaps for Performance Reproduction

### 1. Missing: Actual Framework Versions ⚠️

**What you have**: "spring-webmvc instrumentation applied"  
**What you need**: "Spring Boot 3.0.1, Spring WebMVC 6.0.2"

**Impact**: HIGH - Can't reproduce exact environment

**Workaround**: Check `classpath.txt` and manually parse JARs (error-prone)

**Solution Needed**: Extract from Maven/Gradle metadata or JAR manifests

```java
// Pseudo-code for what's needed
private void addDependencies(ZipOutputStream zip) {
    Map<String, String> deps = new HashMap<>();
    
    // Option 1: Parse from Maven/Gradle (best)
    // deps = MavenDependencyExtractor.extract();
    
    // Option 2: Scan classpath JARs (fallback)
    for (String jarPath : System.getProperty("java.class.path").split(":")) {
        if (jarPath.endsWith(".jar")) {
            String version = extractVersionFromJar(jarPath);
            deps.put(getArtifactName(jarPath), version);
        }
    }
    
    TracerFlare.addText(zip, "dependencies.json", toJson(deps));
}
```

### 2. Missing: Load Profile ❌

**What you need**:
- Request rate (requests/sec or traces/sec)
- Concurrency level (active spans at capture time)
- Traffic pattern over last 5-10 minutes

**Impact**: CRITICAL - Can't reproduce performance conditions

**Solution Needed**: Track and include rate calculations

```java
// Pseudo-code for what's needed
private void addLoadProfile(ZipOutputStream zip) {
    long captureTimeMs = System.currentTimeMillis();
    
    // Calculate rates from health metrics
    LoadProfile profile = new LoadProfile();
    profile.tracesPerSecond = calculateRate(healthMetrics.flushedTraces, captureTimeMs);
    profile.spansPerSecond = calculateRate(healthMetrics.finishedSpans, captureTimeMs);
    profile.activeSpans = pendingTraceBuffer.size();
    profile.queueDepth = traceWriter.queueSize();
    
    // Historical data (last 10 min)
    profile.rateHistory = getRateHistory(10, TimeUnit.MINUTES);
    
    TracerFlare.addText(zip, "load_profile.json", toJson(profile));
}
```

### 3. Missing: Trace Statistics ❌

**What you need**:
- p50/p95/p99 latency per endpoint
- Error rate per endpoint
- Trace count per endpoint
- Slowest traces identified

**Impact**: HIGH - No aggregated view of performance

**Current State**: You get 50 random traces, but no statistical analysis

**Solution Needed**: Aggregate stats from trace buffer

```java
// Pseudo-code for what's needed
private void addTraceStatistics(ZipOutputStream zip) {
    Map<String, EndpointStats> statsByEndpoint = new HashMap<>();
    
    for (Trace trace : getAllRecentTraces()) {
        String endpoint = trace.getRootSpan().getResourceName();
        EndpointStats stats = statsByEndpoint.computeIfAbsent(endpoint, k -> new EndpointStats());
        
        stats.addTrace(trace.getDuration());
        if (trace.hasError()) {
            stats.incrementErrors();
        }
    }
    
    // Calculate percentiles
    for (EndpointStats stats : statsByEndpoint.values()) {
        stats.calculatePercentiles();
    }
    
    TracerFlare.addText(zip, "trace_statistics.json", toJson(statsByEndpoint));
}
```

---

## 🎯 Recommendations

### Immediate Priorities (Week 1-2):

1. **Add Dependency Extraction** (HIGH)
   - Extract from build files (pom.xml, build.gradle)
   - Fallback to JAR manifest scanning
   - Format: JSON with `groupId:artifactId:version`

2. **Add Load Profile** (CRITICAL)
   - Track traces/sec, spans/sec
   - Capture concurrent span count
   - Include 5-10 min historical rates

3. **Enhance Trace Collection** (HIGH)
   - Identify and flag slow traces (p99+)
   - Add trace statistics (p50/p95/p99 by endpoint)
   - Increase sample size to 100 traces

### Medium-Term (Week 3-6):

4. **Improve Instrumentation State**
   - Include detected framework versions
   - Link instrumentation names to actual JARs
   - Show version ranges matched

5. **Add Trace Aggregation**
   - Group by endpoint/operation
   - Calculate statistics
   - Identify anomalies

### Long-Term (Month 2-3):

6. **Auto-Generate Reproduction Guide**
   - Use dependencies + load profile
   - Create benchmark script
   - Provide setup instructions

---

## 📝 Example: What a Complete Flare Would Look Like

```
tracer_flare_1234567890.zip
├── flare_info.txt              ✅ HAS (timestamps)
├── tracer_version.txt          ✅ HAS (1.41.0)
├── initial_config.txt          ✅ HAS
├── dynamic_config.txt          ✅ HAS
├── jvm_args.txt                ✅ HAS
├── classpath.txt               ✅ HAS
├── library_path.txt            ✅ HAS
├── threads.txt                 ✅ HAS (optional)
├── tracer.log                  ✅ HAS
├── profiler_config.txt         ✅ HAS
├── profiler_log.txt            ✅ HAS
├── pending_traces.txt          ✅ HAS (50 traces)
├── tracer_health.txt           ✅ HAS (counters)
├── span_metrics.txt            ✅ HAS
├── instrumenter_state.txt      ✅ HAS (names only)
├── instrumenter_metrics.txt    ✅ HAS
├── dynamic_instrumentation.txt ✅ HAS
├── jmxfetch.txt                ✅ HAS
├── dependencies.json           ❌ MISSING - Need to add!
├── load_profile.json           ❌ MISSING - Need to add!
├── trace_statistics.json       ❌ MISSING - Need to add!
├── slow_traces.json            ❌ MISSING - Would be nice!
└── reproduction_guide.md       ❌ MISSING - Would be amazing!
```

---

## 🔍 How to Verify What's in Your Flare

If you have a flare ZIP, check:

```bash
# List all files
unzip -l tracer-flare-*.zip

# Check for tracer version
unzip -p tracer-flare-*.zip tracer_version.txt

# Check instrumentation state
unzip -p tracer-flare-*.zip instrumenter_state.txt | head -20

# Count traces
unzip -p tracer-flare-*.zip pending_traces.txt | wc -l

# Check for dependencies (will be empty currently)
unzip -l tracer-flare-*.zip | grep dependencies

# Check for load profile (will be empty currently)
unzip -l tracer-flare-*.zip | grep load_profile
```

---

## 📚 Related Documents

- [Java Tracer Flare Contents](TRACER_FLARE_CONTENTS.md) - Complete documentation of current implementation
- [Tracer Flare Comparison](TRACER_FLARE_COMPARISON.md) - Java vs Python comparison
- [Ideal Design](TRACER_FLARE_IDEAL_DESIGN.md) - Comprehensive ideal design for reproduction
- [Action Items](TRACER_FLARE_ACTION_ITEMS.md) - Prioritized implementation plan

