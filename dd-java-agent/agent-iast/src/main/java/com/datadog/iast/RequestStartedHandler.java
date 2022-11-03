package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.gateway.Flow;

public class RequestStartedHandler implements Supplier<Flow<Object>> {

  private final OverheadController overheadController;
  private final Reporter reporter;

  public RequestStartedHandler(
      final OverheadController overheadController, final Reporter reporter) {
    this.overheadController = overheadController;
    this.reporter = reporter;
  }

  @Override
  public Flow<Object> get() {
    if (!overheadController.acquireRequest()) {
      return Flow.ResultFlow.empty();
    }
    return new Flow.ResultFlow<>(new IastRequestContext(overheadController, reporter));
  }
}
