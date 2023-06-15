package com.datadog.iast;

import com.datadog.iast.HasDependencies.Dependencies;
import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.gateway.Flow;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public class RequestStartedHandler implements Supplier<Flow<Object>> {

  private final OverheadController overheadController;

  public RequestStartedHandler(@Nonnull final Dependencies dependencies) {
    this.overheadController = dependencies.getOverheadController();
  }

  @Override
  public Flow<Object> get() {
    if (!overheadController.acquireRequest()) {
      return Flow.ResultFlow.empty();
    }
    return new Flow.ResultFlow<>(newContext());
  }

  protected IastRequestContext newContext() {
    return new IastRequestContext();
  }
}
