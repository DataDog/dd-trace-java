package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Represents a Datadog wall-clock method sample event. */
@JfrType("datadog.MethodSample")
public interface MethodSample {
  long startTime();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();

  long spanId();

  long localRootSpanId();
}
