package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.StackFrame")
public interface JfrStackFrame {
  JfrMethod method();

  @JfrField(value = "method", raw = true)
  long methodId();

  int lineNumber();
}
