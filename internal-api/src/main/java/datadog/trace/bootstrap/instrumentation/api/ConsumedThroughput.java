package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

public class ConsumedThroughput extends ThroughputBase {
  public ConsumedThroughput(long hash, long originTimestampNanos, long serviceStartTimestampNanos, @Nullable Long parentStartTimestampNanos) {
    super(hash, originTimestampNanos, serviceStartTimestampNanos, parentStartTimestampNanos);
  }
}
