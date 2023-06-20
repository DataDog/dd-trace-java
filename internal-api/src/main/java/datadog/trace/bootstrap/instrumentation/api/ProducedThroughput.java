package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

public class ProducedThroughput extends ThroughputBase {
  public ProducedThroughput(long hash, long originTimestampNanos, long serviceStartTimestampNanos, @Nullable Long parentStartTimestampNanos) {
    super(hash, originTimestampNanos, serviceStartTimestampNanos, parentStartTimestampNanos);
  }
}
