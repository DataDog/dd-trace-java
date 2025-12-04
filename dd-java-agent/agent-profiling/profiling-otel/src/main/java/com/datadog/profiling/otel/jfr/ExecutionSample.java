package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Represents a Datadog CPU execution sample event. */
@JfrType("datadog.ExecutionSample")
public interface ExecutionSample {
  long startTime();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();

  long spanId();

  long localRootSpanId();
}
