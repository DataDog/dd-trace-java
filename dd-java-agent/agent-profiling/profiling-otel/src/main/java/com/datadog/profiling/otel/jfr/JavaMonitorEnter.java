package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

@JfrType("jdk.JavaMonitorEnter")
public interface JavaMonitorEnter {
  long startTime();

  long duration();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();
}
