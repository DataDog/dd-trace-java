package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

public class TerminatedThroughput extends ThroughputBase {
  public TerminatedThroughput(long hash, long originTimestampNanos, long serviceStartTimestampNanos) {
    super(hash, originTimestampNanos, serviceStartTimestampNanos);
  }
}
