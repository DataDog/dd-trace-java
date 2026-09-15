package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

@JfrType("jdk.JavaMonitorWait")
public interface JavaMonitorWait {
  long startTime();

  long duration();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();
}
