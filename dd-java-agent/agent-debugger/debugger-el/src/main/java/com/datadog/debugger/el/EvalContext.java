package com.datadog.debugger.el;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;

public class EvalContext {
  private final ValueReferenceResolver valueRefResolver;
  private final TimeoutChecker timeoutChecker;

  public EvalContext(
      final ValueReferenceResolver valueRefResolver, final TimeoutChecker timeoutChecker) {
    this.valueRefResolver = valueRefResolver;
    this.timeoutChecker = timeoutChecker;
  }

  public ValueReferenceResolver getValueRefResolver() {
    return valueRefResolver;
  }

  public TimeoutChecker getTimeoutChecker() {
    return timeoutChecker;
  }
}
