package com.datadog.iast;

import static datadog.trace.api.ProductActivation.FULLY_ENABLED;

import com.datadog.iast.HasDependencies.Dependencies;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
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
    final ProductActivation activation = Config.get().getIastActivation();
    final TaintedObjects taintedObjects =
        activation.isAtLeast(FULLY_ENABLED)
            ? TaintedObjects.acquire()
            : TaintedObjects.NoOp.INSTANCE;
    return new IastRequestContext(taintedObjects);
  }
}
