package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrType;

@JfrType("java.lang.Class")
public interface JfrClass {
  String name();
}
