package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;

public class Metadata {
  private final long threadId;
  private final UTF8BytesString threadName;
  private final Map<String, Object> tags;
  private final Map<String, String> baggage;

  public Metadata(
      long threadId,
      UTF8BytesString threadName,
      Map<String, Object> tags,
      Map<String, String> baggage) {
    this.threadId = threadId;
    this.threadName = threadName;
    this.tags = tags;
    this.baggage = baggage;
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
}
