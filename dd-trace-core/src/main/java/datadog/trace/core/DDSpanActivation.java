package datadog.trace.core;

public final class DDSpanActivation {
  public final byte flag;
  public final long threadId;
  public final long timestamp;

  public DDSpanActivation(byte flag, long threadId, long timestamp) {
    this.flag = flag;
    this.threadId = threadId;
    this.timestamp = timestamp;
  }
}
