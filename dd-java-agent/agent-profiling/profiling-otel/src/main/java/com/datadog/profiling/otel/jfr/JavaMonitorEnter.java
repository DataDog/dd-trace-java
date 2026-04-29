package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Represents a JDK JavaMonitorEnter event for lock contention. */
@JfrType("jdk.JavaMonitorEnter")
public interface JavaMonitorEnter {
  long startTime();

  long duration();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();
}
