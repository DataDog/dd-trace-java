package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;

public final class Metadata {
  private final long threadId;
  private final UTF8BytesString threadName;
  private final Map<String, Object> tags;
  private final Map<String, String> baggage;

  private final int samplingPriority;
  private final boolean measured;
  private final boolean topLevel;

  public Metadata(
      long threadId,
      UTF8BytesString threadName,
      Map<String, Object> tags,
      Map<String, String> baggage,
      int samplingPriority,
      boolean measured,
      boolean topLevel) {
    this.threadId = threadId;
    this.threadName = threadName;
    this.tags = tags;
    this.baggage = baggage;
    this.samplingPriority = samplingPriority;
    this.measured = measured;
    this.topLevel = topLevel;
  }

  public long getThreadId() {
    return threadId;
  }

  public UTF8BytesString getThreadName() {
    return threadName;
  }

  public Map<String, Object> getTags() {
    return tags;
  }

  public Map<String, String> getBaggage() {
    return baggage;
  }

  public boolean measured() {
    return measured;
  }

  public boolean topLevel() {
    return topLevel;
  }

  public boolean hasSamplingPriority() {
    return samplingPriority != UNSET;
  }

  public int samplingPriority() {
    return samplingPriority;
  }
}
