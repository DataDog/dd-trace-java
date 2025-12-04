package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/** Represents a Datadog object allocation sample event. */
@JfrType("datadog.ObjectSample")
public interface ObjectSample {
  long startTime();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();

  long spanId();

  long localRootSpanId();

  long allocationSize();
}
