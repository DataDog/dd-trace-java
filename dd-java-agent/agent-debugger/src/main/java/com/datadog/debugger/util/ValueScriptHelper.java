package com.datadog.debugger.util;

import static datadog.trace.bootstrap.debugger.util.TimeoutChecker.DEFAULT_TIME_OUT;

import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;

public class ValueScriptHelper {
  private static final Limits LIMITS = new Limits(1, 3, 255, 5);

  public static void serializeValue(
      StringBuilder sb, String expr, Object value, CapturedContext.Status status) {
    SerializerWithLimits serializer =
        new SerializerWithLimits(
            new StringTokenWriter(sb, status.getErrors()), new TimeoutChecker(DEFAULT_TIME_OUT));
    try {
      serializer.serialize(value, value != null ? value.getClass().getTypeName() : null, LIMITS);
    } catch (Exception ex) {
      status.addError(new EvaluationError(expr, ex.getMessage()));
    }
  }
}
