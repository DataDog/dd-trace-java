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
    if (currentTimeMillis >= lastSampleMillis + SAMPLE_INTERVAL_MILLIS) {
      synchronized (this) {
        if (currentTimeMillis >= lastSampleMillis + SAMPLE_INTERVAL_MILLIS) {
          lastSampleMillis = currentTimeMillis;
          return weight.getAndSet(0);
        }
      }
    }
    return 0;
  }

  public boolean canSample(long currentTimeMillis) {
    weight.incrementAndGet();
    return currentTimeMillis >= lastSampleMillis + SAMPLE_INTERVAL_MILLIS;
  }
}
