package com.datadog.debugger.util;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class ValueScriptHelper {
  public static void serializeValue(
      StringBuilder sb, String expr, Object value, CapturedContext.Status status, Limits limits) {
    Duration timeout =
        Duration.of(Config.get().getDynamicInstrumentationCaptureTimeout(), ChronoUnit.MILLIS);
    TimeoutChecker timeoutChecker = new TimeoutChecker(timeout);
    SerializerWithLimits serializer =
        new SerializerWithLimits(new StringTokenWriter(sb, status.getErrors()), timeoutChecker);
    try {
      serializer.serialize(
          value,
          value != null ? value.getClass().getTypeName() : Object.class.getTypeName(),
          limits);
    } catch (Exception ex) {
      status.addError(new EvaluationError(expr, ex.getMessage()));
    }
  }
}
