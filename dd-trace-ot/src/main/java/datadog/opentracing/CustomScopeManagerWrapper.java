package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;

/**
 * Allows custom OpenTracing ScopeManagers used by CoreTracer
 *
 * <p>Normal case:
 *
 * <p>CoreTracer.scopeManager = ContextualScopeManager
 *
 * <p>DDTracer.scopeManager = OTScopeManager wrapping CoreTracer.scopeManager
 *
 * <p>Custom case:
 *
 * <p>CoreTracer.scopeManager = CustomScopeManagerWrapper wrapping passed in scopemanager
 *
 * <p>DDTracer.scopeManager = passed in scopemanager
 */
class CustomScopeManagerWrapper implements AgentScopeManager {
  private final ScopeManager delegate;
  private final TypeConverter converter;

  CustomScopeManagerWrapper(final ScopeManager scopeManager, final TypeConverter converter) {
    delegate = scopeManager;
    this.converter = converter;
  }

  @Override
  public AgentScope activate(final AgentSpan agentSpan, final ScopeSource source) {
    final Span span = converter.toSpan(agentSpan);
    final Scope scope = delegate.activate(span);
    return converter.toAgentScope(scope);
  }

  @Override
  public AgentScope activate(
      final AgentSpan agentSpan, final ScopeSource source, boolean isAsyncPropagating) {
    final Span span = converter.toSpan(agentSpan);
    final Scope scope = delegate.activate(span);
    final AgentScope agentScope = converter.toAgentScope(scope);
    agentScope.setAsyncPropagation(isAsyncPropagating);
    return agentScope;
  }

  @Override
  public AgentScope active() {
    return converter.toAgentScope(delegate.active());
  }

  @Override
  public AgentSpan activeSpan() {
    return converter.toAgentSpan(delegate.activeSpan());
  }

  @Override
  public AgentScope.Continuation captureSpan(final AgentSpan span, ScopeSource source) {
    // I can't see a better way to do this, and I don't know if this even makes sense.
    try (AgentScope scope = this.activate(span, source)) {
      return scope.capture();
    }
  }
}
