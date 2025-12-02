package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a JDK JavaMonitorWait event for lock contention. */
@JfrType("jdk.JavaMonitorWait")
public interface JavaMonitorWait {
  long startTime();

  long duration();

  JfrStackTrace stackTrace();
}
