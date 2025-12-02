package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a JFR stack frame. */
@JfrType("jdk.types.StackFrame")
public interface JfrStackFrame {
  JfrMethod method();

  int lineNumber();
}
