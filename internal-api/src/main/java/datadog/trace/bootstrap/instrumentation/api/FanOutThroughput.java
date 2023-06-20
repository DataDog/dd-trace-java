package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

public class FanOutThroughput extends ThroughputBase {
  public FanOutThroughput(long hash, long originTimestampNanos, long serviceStartTimestampNanos, @Nullable Long parentStartTimestampNanos) {
    super(hash, originTimestampNanos, serviceStartTimestampNanos, parentStartTimestampNanos);
  }
}
