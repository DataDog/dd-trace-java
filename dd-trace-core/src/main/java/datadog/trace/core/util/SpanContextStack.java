package datadog.trace.core.util;

import datadog.trace.core.monitor.Monitor;

public class SpanContextStack extends Throwable {
  public SpanContextStack(Origin origin, Monitor monitor) {
    monitor.onSpanContextStack(origin);
  }

  public enum Origin {
    ROOT,
    CLIENT
  }
}
