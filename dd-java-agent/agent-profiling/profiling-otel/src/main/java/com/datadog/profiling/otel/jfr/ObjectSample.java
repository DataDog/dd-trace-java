package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a Datadog object allocation sample event. */
@JfrType("datadog.ObjectSample")
public interface ObjectSample {
  long startTime();

  JfrStackTrace stackTrace();

  long spanId();

  long localRootSpanId();

  long allocationSize();
}
