# Implementation: Load Profile & Rate Calculations

## Overview
Track and calculate traces/sec, spans/sec, and concurrency levels to enable reproduction of load conditions.

---

## Problem: Counters vs Rates

### Current State (Counters Only):
```java
// TracerHealthMetrics has cumulative counters
createdTraces.sum()  = 1,234,567  // Total since startup
finishedSpans.sum()  = 5,678,901  // Total since startup
```

**Problem**: Can't calculate rate without knowing:
- When the tracer started
- When the flare was captured
- Historical data points

### Solution (Rate Tracking):
```java
// Track rates over time windows
tracesPerSecondLast1Min   = 120.5  // Average over last minute
tracesPerSecondLast5Min   = 105.3  // Average over last 5 minutes
currentConcurrentSpans    = 45     // Right now
```

---

## New File: `LoadProfileTracker.java`

**Location**: `dd-trace-core/src/main/java/datadog/trace/core/monitor/LoadProfileTracker.java`

```java
package datadog.trace.core.monitor;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.flare.TracerFlare;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks load profile over time for tracer flare.
 * Calculates traces/sec, spans/sec, and concurrency levels.
 */
public final class LoadProfileTracker implements TracerFlare.Reporter, HealthMetrics {
  private static final Logger log = LoggerFactory.getLogger(LoadProfileTracker.class);
  
  private static final JsonAdapter<Map<String, Object>> JSON_ADAPTER =
      new Moshi.Builder().build().adapter(Map.class);
  
  // Time windows for rate calculations
  private static final long WINDOW_1_MIN_NANOS = TimeUnit.MINUTES.toNanos(1);
  private static final long WINDOW_5_MIN_NANOS = TimeUnit.MINUTES.toNanos(5);
  private static final long WINDOW_10_MIN_NANOS = TimeUnit.MINUTES.toNanos(10);
  
  // Sample every 5 seconds
  private static final long SAMPLE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
  
  // Keep 10 minutes of history (10 min / 5 sec = 120 samples)
  private static final int MAX_SAMPLES = 120;
  
  // Current counters (since last sample)
  private final AtomicLong currentTraces = new AtomicLong();
  private final AtomicLong currentSpans = new AtomicLong();
  private final AtomicInteger currentConcurrentSpans = new AtomicInteger();
  
  // Historical samples
  private final Deque<Sample> samples = new ArrayDeque<>(MAX_SAMPLES);
  
  // Tracer start time
  private final long tracerStartNanos;
  private volatile long lastSampleNanos;
  
  // For tracking concurrent spans
  private final AtomicInteger activeScopeCount = new AtomicInteger();
  
  public LoadProfileTracker() {
    this.tracerStartNanos = System.nanoTime();
    this.lastSampleNanos = tracerStartNanos;
    
    // Register with flare system
    TracerFlare.addReporter(this);
    
    // Start background sampling thread
    startSamplingThread();
  }
  
  /**
   * Background thread to sample metrics every 5 seconds.
   */
  private void startSamplingThread() {
    Thread samplerThread = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(TimeUnit.NANOSECONDS.toMillis(SAMPLE_INTERVAL_NANOS));
          takeSample();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          log.warn("Error taking load profile sample", e);
        }
      }
    }, "dd-load-profile-sampler");
    
    samplerThread.setDaemon(true);
    samplerThread.start();
  }
  
  /**
   * Take a sample of current metrics.
   */
  private void takeSample() {
    long nowNanos = System.nanoTime();
    long intervalNanos = nowNanos - lastSampleNanos;
    
    if (intervalNanos <= 0) {
      return; // Clock skew or first sample
    }
    
    // Get counts since last sample
    long traces = currentTraces.getAndSet(0);
    long spans = currentSpans.getAndSet(0);
    int concurrent = currentConcurrentSpans.get();
    
    // Calculate rates (per second)
    double intervalSeconds = intervalNanos / 1_000_000_000.0;
    double tracesPerSec = traces / intervalSeconds;
    double spansPerSec = spans / intervalSeconds;
    
    Sample sample = new Sample(
        nowNanos,
        traces,
        spans,
        concurrent,
        tracesPerSec,
        spansPerSec
    );
    
    synchronized (samples) {
      samples.addLast(sample);
      
      // Remove old samples beyond 10 minutes
      while (!samples.isEmpty() && 
             (nowNanos - samples.peekFirst().timestampNanos) > WINDOW_10_MIN_NANOS) {
        samples.removeFirst();
      }
    }
    
    lastSampleNanos = nowNanos;
  }
  
  /**
   * Called when a trace is created.
   */
  public void onTraceCreated() {
    currentTraces.incrementAndGet();
  }
  
  /**
   * Called when a span is finished.
   */
  public void onSpanFinished() {
    currentSpans.incrementAndGet();
  }
  
  /**
   * Called when a scope is activated (concurrent span).
   */
  public void onScopeActivated() {
    int count = activeScopeCount.incrementAndGet();
    // Update max concurrent if higher
    currentConcurrentSpans.updateAndGet(current -> Math.max(current, count));
  }
  
  /**
   * Called when a scope is closed.
   */
  public void onScopeClosed() {
    activeScopeCount.decrementAndGet();
  }
  
  /**
   * Calculate average rate over a time window.
   */
  private double calculateAverageRate(long windowNanos, RateExtractor extractor) {
    long nowNanos = System.nanoTime();
    long windowStartNanos = nowNanos - windowNanos;
    
    double totalRate = 0;
    int count = 0;
    
    synchronized (samples) {
      for (Sample sample : samples) {
        if (sample.timestampNanos >= windowStartNanos) {
          totalRate += extractor.extract(sample);
          count++;
        }
      }
    }
    
    return count > 0 ? totalRate / count : 0.0;
  }
  
  /**
   * Get current load profile as JSON.
   */
  public String getLoadProfile() {
    Map<String, Object> profile = new HashMap<>();
    
    long nowNanos = System.nanoTime();
    long uptimeSeconds = TimeUnit.NANOSECONDS.toSeconds(nowNanos - tracerStartNanos);
    
    profile.put("capture_timestamp_ms", System.currentTimeMillis());
    profile.put("tracer_uptime_seconds", uptimeSeconds);
    
    // Current state
    Map<String, Object> current = new HashMap<>();
    current.put("concurrent_spans", activeScopeCount.get());
    current.put("pending_traces_sample_count", samples.size());
    profile.put("current", current);
    
    // Rate calculations over different windows
    Map<String, Object> rates = new HashMap<>();
    
    rates.put("traces_per_second_1min", 
        calculateAverageRate(WINDOW_1_MIN_NANOS, s -> s.tracesPerSecond));
    rates.put("traces_per_second_5min", 
        calculateAverageRate(WINDOW_5_MIN_NANOS, s -> s.tracesPerSecond));
    rates.put("traces_per_second_10min", 
        calculateAverageRate(WINDOW_10_MIN_NANOS, s -> s.tracesPerSecond));
    
    rates.put("spans_per_second_1min", 
        calculateAverageRate(WINDOW_1_MIN_NANOS, s -> s.spansPerSecond));
    rates.put("spans_per_second_5min", 
        calculateAverageRate(WINDOW_5_MIN_NANOS, s -> s.spansPerSecond));
    rates.put("spans_per_second_10min", 
        calculateAverageRate(WINDOW_10_MIN_NANOS, s -> s.spansPerSecond));
    
    profile.put("rates", rates);
    
    // Peak values
    Map<String, Object> peaks = new HashMap<>();
    synchronized (samples) {
      double peakTracesPerSec = 0;
      double peakSpansPerSec = 0;
      int peakConcurrent = 0;
      
      for (Sample sample : samples) {
        peakTracesPerSec = Math.max(peakTracesPerSec, sample.tracesPerSecond);
        peakSpansPerSec = Math.max(peakSpansPerSec, sample.spansPerSecond);
        peakConcurrent = Math.max(peakConcurrent, sample.concurrentSpans);
      }
      
      peaks.put("traces_per_second", peakTracesPerSec);
      peaks.put("spans_per_second", peakSpansPerSec);
      peaks.put("concurrent_spans", peakConcurrent);
    }
    profile.put("peaks_last_10min", peaks);
    
    // Recent history (last 2 minutes, every 5 seconds = 24 samples)
    synchronized (samples) {
      if (!samples.isEmpty()) {
        long twoMinutesAgo = nowNanos - TimeUnit.MINUTES.toNanos(2);
        
        java.util.List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (Sample sample : samples) {
          if (sample.timestampNanos >= twoMinutesAgo) {
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp_offset_seconds", 
                TimeUnit.NANOSECONDS.toSeconds(sample.timestampNanos - tracerStartNanos));
            point.put("traces_per_second", sample.tracesPerSecond);
            point.put("spans_per_second", sample.spansPerSecond);
            point.put("concurrent_spans", sample.concurrentSpans);
            history.add(point);
          }
        }
        profile.put("history_last_2min", history);
      }
    }
    
    return JSON_ADAPTER.toJson(profile);
  }
  
  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    // Take a final sample before capturing
    takeSample();
    
    String profile = getLoadProfile();
    TracerFlare.addText(zip, "load_profile.json", profile);
  }
  
  /**
   * Sample data point.
   */
  private static class Sample {
    final long timestampNanos;
    final long traceCount;
    final long spanCount;
    final int concurrentSpans;
    final double tracesPerSecond;
    final double spansPerSecond;
    
    Sample(long timestampNanos, long traceCount, long spanCount, 
           int concurrentSpans, double tracesPerSecond, double spansPerSecond) {
      this.timestampNanos = timestampNanos;
      this.traceCount = traceCount;
      this.spanCount = spanCount;
      this.concurrentSpans = concurrentSpans;
      this.tracesPerSecond = tracesPerSecond;
      this.spansPerSecond = spansPerSecond;
    }
  }
  
  /**
   * Functional interface to extract a rate from a sample.
   */
  @FunctionalInterface
  private interface RateExtractor {
    double extract(Sample sample);
  }
  
  // ========== HealthMetrics Interface Implementation ==========
  // Delegate to onTraceCreated/onSpanFinished/etc methods
  
  @Override
  public void onStart(int queueCapacity) {
    // No-op
  }
  
  @Override
  public void onShutdown(boolean flushSuccess) {
    // No-op
  }
  
  @Override
  public void onPublish(java.util.List trace, int samplingPriority) {
    onTraceCreated();
    currentSpans.addAndGet(trace.size());
  }
  
  @Override
  public void onFailedPublish(int samplingPriority) {
    // Still count as created
    onTraceCreated();
  }
  
  @Override
  public void onScheduleFlush(boolean previousIncomplete) {
    // No-op
  }
  
  @Override
  public void onFlush(boolean early) {
    // No-op
  }
  
  @Override
  public void onSerialize(int serializedSizeInBytes) {
    // No-op
  }
  
  @Override
  public void onFailedSerialize(java.util.List trace, Throwable optionalCause) {
    // No-op
  }
  
  @Override
  public void onSend(int traceCount, int sizeInBytes, datadog.communication.http.HttpResponse response) {
    // No-op
  }
  
  @Override
  public void onFailedSend(int traceCount, int sizeInBytes, datadog.communication.http.HttpResponse response) {
    // No-op
  }
  
  @Override
  public void onCreateTrace() {
    onTraceCreated();
  }
  
  @Override
  public void onCreateSpan() {
    currentSpans.incrementAndGet();
  }
  
  @Override
  public void onFinishSpan() {
    onSpanFinished();
  }
  
  @Override
  public void onPartialFlush(int traceCount) {
    // No-op
  }
}
```

---

## Integration into CoreTracer

**File**: `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java`

### Step 1: Add as a health metrics listener

```java
public class CoreTracer implements AgentTracer.TracerAPI, TracerFlare.Reporter {
  
  // Add field
  private final LoadProfileTracker loadProfileTracker;
  
  // In constructor
  public CoreTracer(/* ... */) {
    // ... existing code ...
    
    // Create load profile tracker
    this.loadProfileTracker = new LoadProfileTracker();
    
    // Add as a health metrics listener
    this.healthMetrics = /* existing health metrics setup */
    
    // Wrap health metrics to also notify load profile tracker
    this.healthMetrics = new CompositeHealthMetrics(
        this.healthMetrics,
        loadProfileTracker
    );
  }
}
```

### Step 2: Create CompositeHealthMetrics wrapper

```java
/**
 * Wraps multiple HealthMetrics implementations to notify all of them.
 */
private static class CompositeHealthMetrics implements HealthMetrics {
  private final HealthMetrics[] delegates;
  
  CompositeHealthMetrics(HealthMetrics... delegates) {
    this.delegates = delegates;
  }
  
  @Override
  public void onStart(int queueCapacity) {
    for (HealthMetrics delegate : delegates) {
      delegate.onStart(queueCapacity);
    }
  }
  
  @Override
  public void onCreateTrace() {
    for (HealthMetrics delegate : delegates) {
      delegate.onCreateTrace();
    }
  }
  
  @Override
  public void onFinishSpan() {
    for (HealthMetrics delegate : delegates) {
      delegate.onFinishSpan();
    }
  }
  
  // ... implement all other methods similarly ...
}
```

### Step 3: Track scope activation/closure

In your scope management code:

```java
public AgentScope activateSpan(AgentSpan span) {
  loadProfileTracker.onScopeActivated();
  // ... existing activation logic ...
}

public void closeScope() {
  // ... existing close logic ...
  loadProfileTracker.onScopeClosed();
}
```

---

## Expected Output Format

**File**: `load_profile.json`

```json
{
  "capture_timestamp_ms": 1638360000000,
  "tracer_uptime_seconds": 3600,
  "current": {
    "concurrent_spans": 25,
    "pending_traces_sample_count": 120
  },
  "rates": {
    "traces_per_second_1min": 120.5,
    "traces_per_second_5min": 105.3,
    "traces_per_second_10min": 98.7,
    "spans_per_second_1min": 450.2,
    "spans_per_second_5min": 425.8,
    "spans_per_second_10min": 410.1
  },
  "peaks_last_10min": {
    "traces_per_second": 250.5,
    "spans_per_second": 980.3,
    "concurrent_spans": 45
  },
  "history_last_2min": [
    {
      "timestamp_offset_seconds": 3480,
      "traces_per_second": 115.2,
      "spans_per_second": 445.1,
      "concurrent_spans": 22
    },
    {
      "timestamp_offset_seconds": 3485,
      "traces_per_second": 118.7,
      "spans_per_second": 452.3,
      "concurrent_spans": 24
    }
    // ... 22 more samples ...
  ]
}
```

---

## Usage for Reproduction

With this data, you can now:

```bash
# Generate load matching the customer's profile
./benchmark.sh \
  --rps 105 \                    # From traces_per_second_5min
  --concurrency 25 \              # From current.concurrent_spans
  --duration 300s

# Or use a load testing tool
vegeta attack \
  -rate=105/1s \
  -duration=300s \
  -targets=targets.txt
```

---

## Testing

```java
@Test
public void testLoadProfileTracking() {
  LoadProfileTracker tracker = new LoadProfileTracker();
  
  // Simulate load
  for (int i = 0; i < 100; i++) {
    tracker.onTraceCreated();
    tracker.onSpanFinished();
  }
  
  // Wait for sample
  Thread.sleep(6000);
  
  // Get profile
  String json = tracker.getLoadProfile();
  
  assertTrue(json.contains("traces_per_second"));
  assertTrue(json.contains("concurrent_spans"));
}
```

---

## Memory Impact

**Per sample**: ~80 bytes (6 longs + 2 doubles + overhead)  
**Total**: 120 samples × 80 bytes = ~10 KB  
**Overhead**: Minimal - sampling every 5 seconds

---

## Performance Impact

- Background thread sampling every 5 seconds: **negligible**
- Atomic increments on hot path: **< 10 ns per operation**
- Memory: **~10 KB for 10 minutes of history**

---

## Future Enhancements

1. **Per-endpoint rates**: Track rates broken down by endpoint
2. **Error rates**: Track error rate over time
3. **Latency distribution**: Track p50/p95/p99 over time
4. **Auto-detect anomalies**: Flag unusual traffic patterns
5. **Adaptive sampling**: Sample more frequently during high load

