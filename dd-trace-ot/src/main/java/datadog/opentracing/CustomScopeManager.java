package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import datadog.trace.core.scopemanager.DDScopeManager;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import java.util.Objects;

/**
 * Allows custom OpenTracing scope managers used by CoreTracer
 *
 * <p>Normal case:
 *
 * <p>CoreTracer.scopeManager = ContextualScopeManager
 *
 * <p>DDTracer.scopeManager = OTScopeManager wrapping CoreTracer.scopeManager
 *
 * <p>Custom case:
 *
 * <p>CoreTracer.scopeManager = CustomScopeManager wrapping passed in scopemanager
 *
 * <p>DDTracer.scopeManager = passed in scopemanager
 */
class CustomScopeManager implements DDScopeManager {
  private final ScopeManager delegate;
  private final Converter converter;

  CustomScopeManager(final ScopeManager scopeManager, final Converter converter) {
    this.delegate = scopeManager;
    this.converter = converter;
  }

  @Override
  public AgentScope activate(final AgentSpan agentSpan, final boolean finishOnClose) {
    final Span span = converter.toSpan(agentSpan);
    final Scope scope = delegate.activate(span, finishOnClose);

    return new CustomScopeManagerScope(scope);
  }

  @Override
  public TraceScope active() {
    return new CustomScopeManagerScope(delegate.active());
  }

  @Override
  public AgentSpan activeSpan() {
    return converter.toAgentSpan(delegate.activeSpan());
  }

  class CustomScopeManagerScope implements AgentScope, TraceScope {
    private final Scope delegate;
    private final boolean traceScope;

    private CustomScopeManagerScope(final Scope delegate) {
      this.delegate = delegate;

      // Handle case where the custom scope manager returns TraceScopes
      traceScope = delegate instanceof TraceScope;
    }

    @Override
    public AgentSpan span() {
      return converter.toAgentSpan(delegate.span());
    }

    @Override
    public void setAsyncPropagation(final boolean value) {
      if (traceScope) {
        ((TraceScope) delegate).setAsyncPropagation(value);
      }
    }

    @Override
    public boolean isAsyncPropagating() {
      return traceScope && ((TraceScope) delegate).isAsyncPropagating();
    }

    @Override
    public Continuation capture() {
      if (traceScope) {
        return ((TraceScope) delegate).capture();
      } else {
        return null;
      }
    }

    @Override
    public void close() {
      delegate.close();
    }

    Scope getDelegate() {
      return delegate;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final CustomScopeManagerScope that = (CustomScopeManagerScope) o;
      return delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
      return Objects.hash(delegate);
    }
  }
}
