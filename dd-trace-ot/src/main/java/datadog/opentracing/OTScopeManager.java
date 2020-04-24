package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import datadog.trace.core.CoreTracer;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import java.util.Objects;

/** One of the two possible scope managers. See CustomScopeManager */
class OTScopeManager implements ScopeManager {
  private final Converter converter;
  private final CoreTracer coreTracer;

  OTScopeManager(final CoreTracer coreTracer, final Converter converter) {
    this.coreTracer = coreTracer;
    this.converter = converter;
  }

  @Override
  public Scope activate(final Span span) {
    return activate(span, false);
  }

  @Override
  public Scope activate(final Span span, final boolean finishSpanOnClose) {
    final AgentSpan agentSpan = converter.toAgentSpan(span);
    final AgentScope agentScope = coreTracer.activateSpan(agentSpan, finishSpanOnClose);

    return converter.toScope(agentScope);
  }

  @Override
  public Scope active() {
    return converter.toScope(coreTracer.activeScope());
  }

  @Override
  public Span activeSpan() {
    return converter.toSpan(coreTracer.activeSpan());
  }

  static class OTScope implements Scope {
    private final AgentScope delegate;
    private final Converter converter;

    OTScope(final AgentScope delegate, final Converter converter) {
      this.delegate = delegate;
      this.converter = converter;
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public Span span() {
      return converter.toSpan(delegate.span());
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final OTScope otScope = (OTScope) o;
      return delegate.equals(otScope.delegate);
    }

    @Override
    public int hashCode() {
      return Objects.hash(delegate);
    }
  }

  static class OTTraceScope extends OTScope implements TraceScope {
    private final TraceScope delegate;

    OTTraceScope(final TraceScope delegate, final Converter converter) {
      // All instances of TraceScope implement agent scope (but not vice versa)
      super((AgentScope) delegate, converter);

      this.delegate = delegate;
    }

    @Override
    public Continuation capture() {
      return delegate.capture();
    }

    @Override
    public boolean isAsyncPropagating() {
      return delegate.isAsyncPropagating();
    }

    @Override
    public void setAsyncPropagation(final boolean value) {
      delegate.setAsyncPropagation(value);
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final OTTraceScope that = (OTTraceScope) o;
      return delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
      return Objects.hash(delegate);
    }
  }
}
