package datadog.trace.correlation;

import datadog.trace.api.internal.InternalTracer;

abstract class AbstractCorrelationIdInjector {
  AbstractCorrelationIdInjector(InternalTracer tracer) {
    tracer.addScopeListener(this::afterScopeActivatedCallback, this::afterScopeClosedCallback);
  }

  protected abstract void afterScopeActivatedCallback();

  protected abstract void afterScopeClosedCallback();
}
