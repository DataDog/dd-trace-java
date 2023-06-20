package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

public abstract class ThroughputBase implements InboxItem {
  public long getOriginTimestampNanos() {
    return originTimestampNanos;
  }
  public long getServiceStartTimestampNanos() {
    return serviceStartTimestampNanos;
  }
  @Nullable
  public Long getParentStartTimestampNanos() {
    return parentStartTimestampNanos;
  }
  public long getHash() {
    return hash;
  }
  private final long originTimestampNanos;
  private final long serviceStartTimestampNanos;
  private final Long parentStartTimestampNanos;
  private final long hash;

  public ThroughputBase(long hash, long originTimestampNanos, long serviceStartTimestampNanos) {
    this(hash, originTimestampNanos, serviceStartTimestampNanos, null);
  }

  public ThroughputBase(long hash, long originTimestampNanos, long serviceStartTimestampNanos, @Nullable Long parentStartTimestampNanos) {
    this.hash = hash;
    this.originTimestampNanos = originTimestampNanos;
    this.serviceStartTimestampNanos = serviceStartTimestampNanos;
    this.parentStartTimestampNanos = parentStartTimestampNanos;
  }
}
