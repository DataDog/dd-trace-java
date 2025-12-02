package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a JFR method. */
@JfrType("jdk.types.Method")
public interface JfrMethod {
  JfrClass type();

  String name();
}
