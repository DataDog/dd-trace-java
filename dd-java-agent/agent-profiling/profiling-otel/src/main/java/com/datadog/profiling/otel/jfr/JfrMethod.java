package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

@JfrType("jdk.types.Method")
public interface JfrMethod {
  JfrClass type();

  String name();
}
