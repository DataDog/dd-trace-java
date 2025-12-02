package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a Datadog CPU execution sample event. */
@JfrType("datadog.ExecutionSample")
public interface ExecutionSample {
  long startTime();

  JfrStackTrace stackTrace();

  long spanId();

  long localRootSpanId();
}
