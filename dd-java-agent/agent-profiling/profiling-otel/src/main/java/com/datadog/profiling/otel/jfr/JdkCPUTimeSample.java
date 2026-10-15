package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/**
 * Standard OpenJDK CPU-time sampling event. The OpenJDK profiler records it instead of {@link
 * JdkExecutionSample} on JDK 25+ on Linux; like {@link JdkExecutionSample} it carries no span
 * correlation fields.
 */
@JfrType("jdk.CPUTimeSample")
public interface JdkCPUTimeSample {
  long startTime();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();
}
