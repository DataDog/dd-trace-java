package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.StackTrace")
public interface JfrStackTrace {
  JfrStackFrame[] frames();
}
