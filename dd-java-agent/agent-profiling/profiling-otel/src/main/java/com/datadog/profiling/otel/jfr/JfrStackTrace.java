package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a JFR stack trace. */
@JfrType("jdk.types.StackTrace")
public interface JfrStackTrace {
  JfrStackFrame[] frames();
}
