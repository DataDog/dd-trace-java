package com.datadog.debugger.util;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class ValueScriptHelper {
  /**
   * Serialization limits for log messages. Most values are lower than snapshot because you can
   * directly reference values that are in your interest with Expression Language:
   * obj.field.deepfield or array[1001]
   */
  private static final Limits LIMITS = new Limits(1, 3, 255, 5);

  public static void serializeValue(
      StringBuilder sb, String expr, Object value, CapturedContext.Status status) {
    Duration timeout = Duration.of(Config.get().getDebuggerCaptureTimeout(), ChronoUnit.MILLIS);
    TimeoutChecker timeoutChecker = new TimeoutChecker(timeout);
    SerializerWithLimits serializer =
        new SerializerWithLimits(new StringTokenWriter(sb, status.getErrors()), timeoutChecker);
    try {
      serializer.serialize(
          value,
          value != null ? value.getClass().getTypeName() : Object.class.getTypeName(),
          LIMITS);
    } catch (Exception ex) {
      status.addError(new EvaluationError(expr, ex.getMessage()));
    }
  }
}
