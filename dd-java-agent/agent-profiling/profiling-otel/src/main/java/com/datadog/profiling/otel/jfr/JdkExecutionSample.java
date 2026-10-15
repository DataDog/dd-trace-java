package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/**
 * Standard OpenJDK CPU sampling event. It appears in recordings produced without ddprof — e.g. when
 * the OpenJDK profiler is the only controller-supplied sample source — and carries no span
 * correlation fields.
 */
@JfrType("jdk.ExecutionSample")
public interface JdkExecutionSample {
  long startTime();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();
}
