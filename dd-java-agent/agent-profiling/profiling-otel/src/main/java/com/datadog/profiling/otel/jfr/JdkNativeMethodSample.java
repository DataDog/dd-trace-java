package com.datadog.profiling.otel.jfr;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;

/**
 * Standard OpenJDK native-method sampling event. It appears in recordings produced without ddprof
 * and captures threads sampled in native state; like {@link JdkExecutionSample} it carries no span
 * correlation fields.
 */
@JfrType("jdk.NativeMethodSample")
public interface JdkNativeMethodSample {
  long startTime();

  @JfrField("stackTrace")
  JfrStackTrace stackTrace();

  @JfrField(value = "stackTrace", raw = true)
  long stackTraceId();
}
