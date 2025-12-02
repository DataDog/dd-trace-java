package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a JDK JavaMonitorEnter event for lock contention. */
@JfrType("jdk.JavaMonitorEnter")
public interface JavaMonitorEnter {
  long startTime();

  long duration();

  JfrStackTrace stackTrace();
}
