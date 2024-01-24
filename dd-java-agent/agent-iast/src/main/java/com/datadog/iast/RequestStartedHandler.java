package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.iast.IastContext;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public class RequestStartedHandler implements Supplier<Flow<Object>> {

  private final OverheadController overheadController;
  private final IastContext.Provider contextProvider;

  public RequestStartedHandler(@Nonnull final Dependencies dependencies) {
    this.overheadController = dependencies.getOverheadController();
    this.contextProvider = dependencies.contextProvider;
  }

  @Override
  public Flow<Object> get() {
    if (!overheadController.acquireRequest()) {
      return Flow.ResultFlow.empty();
    }
    return new Flow.ResultFlow<>(newContext());
  }

  protected IastRequestContext newContext() {
    return (IastRequestContext) contextProvider.buildRequestContext();
  }
}
