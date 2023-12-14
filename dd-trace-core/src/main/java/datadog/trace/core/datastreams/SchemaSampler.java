package datadog.trace.core.datastreams;

public class SchemaSampler {
  private static final int SAMPLE_INTERVAL_MILLIS = 30 * 1000;
  private int weight;
  private long lastSampleMillis;

  public SchemaSampler() {
    this.weight = 0;
    this.lastSampleMillis = 0;
  }

  public int shouldSample(long currentTimeMillis) {
    this.weight += 1;
    if (currentTimeMillis >= this.lastSampleMillis + SAMPLE_INTERVAL_MILLIS) {
      int weight = this.weight;
      this.lastSampleMillis = currentTimeMillis;
      this.weight = 0;
      return weight;
    } else {
      return 0;
    }
  }
}
