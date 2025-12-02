package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a Datadog wall-clock method sample event. */
@JfrType("datadog.MethodSample")
public interface MethodSample {
  long startTime();

  JfrStackTrace stackTrace();

  long spanId();

  long localRootSpanId();
}
