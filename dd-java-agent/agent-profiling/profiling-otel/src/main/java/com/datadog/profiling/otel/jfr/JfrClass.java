package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

/** Represents a JFR class. */
@JfrType("java.lang.Class")
public interface JfrClass {
  String name();
}
