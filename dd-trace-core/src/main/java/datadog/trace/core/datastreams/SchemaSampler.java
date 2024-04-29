package datadog.trace.core.datastreams;

import java.util.concurrent.atomic.AtomicInteger;

public class SchemaSampler {
  private static final int SAMPLE_INTERVAL_MILLIS = 30 * 1000;
  private final AtomicInteger weight;
  private volatile long lastSampleMillis;

  public SchemaSampler() {
    this.weight = new AtomicInteger(0);
    this.lastSampleMillis = 0;
  }

  public int trySample(long currentTimeMillis) {
    weight.incrementAndGet();
    if (currentTimeMillis >= lastSampleMillis + SAMPLE_INTERVAL_MILLIS) {
      synchronized (this) {
        if (currentTimeMillis >= lastSampleMillis + SAMPLE_INTERVAL_MILLIS) {
          lastSampleMillis = currentTimeMillis;
          int currentWeight = weight.get();
          weight.set(0);
          return currentWeight;
        }
      }
    }
    return 0;
  }

  public boolean canSample(long currentTimeMillis) {
    return currentTimeMillis >= lastSampleMillis + SAMPLE_INTERVAL_MILLIS;
  }
}
