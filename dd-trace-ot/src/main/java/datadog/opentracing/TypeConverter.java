package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;

// Centralized place to do conversions
class TypeConverter {
  // TODO maybe add caching to reduce new objects being created

  private final LogHandler logHandler;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span == null) {
      return null;
    } else if (span instanceof OTSpan) {
      return ((OTSpan) span).getDelegate();
    } else {
      // NOOP Span
      return AgentTracer.NoopAgentSpan.INSTANCE;
    }
  }

  public OTSpan toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    return new OTSpan(agentSpan, this, logHandler);
  }

  public AgentScope toAgentScope(final Span span, final Scope scope) {
    if (scope == null) {
      return null;
    } else if (scope instanceof OTScopeManager.OTScope) {
      OTScopeManager.OTScope wrapper = (OTScopeManager.OTScope) scope;
      if (wrapper.finishSpanOnClose()) {
        return new FinishingScope(wrapper.unwrap());
      } else {
        return wrapper.unwrap();
      }
    } else {
      return new CustomScope(span, scope);
    }
  }

  public Scope toScope(final AgentScope scope, final boolean finishSpanOnClose) {
    if (scope == null) {
      return null;
    }
    return new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    return new OTSpanContext(context);
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext == null) {
      return null;
    } else if (spanContext instanceof OTSpanContext) {
      return ((OTSpanContext) spanContext).getDelegate();
    } else {
      return AgentTracer.NoopContext.INSTANCE;
    }
  }

  /**
   * Wraps an internal {@link AgentScope} to automatically finish its span when the scope is closed.
   */
  static final class FinishingScope implements AgentScope {
    private final AgentScope delegate;

    private FinishingScope(final AgentScope delegate) {
      this.delegate = delegate;
    }

    @Override
    public AgentSpan span() {
      return delegate.span();
    }

    @Override
    public byte source() {
      return delegate.source();
    }

    @Override
    public void close() {
      delegate.close();
      delegate.span().finish();
    }

    @Override
    public Continuation capture() {
      return delegate.capture();
    }

    @Override
    public Continuation captureConcurrent() {
      return delegate.captureConcurrent();
    }

    @Override
    public boolean checkpointed() {
      return delegate.checkpointed();
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
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o instanceof FinishingScope) {
        return delegate.equals(((FinishingScope) o).delegate);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }
  }

  /** Wraps an external {@link Scope} to look like an internal {@link AgentScope} */
  final class CustomScope implements AgentScope {
    private final Span span;
    private final Scope delegate;

    private CustomScope(final Span span, final Scope delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public AgentSpan span() {
      return toAgentSpan(span);
    }

    @Override
    public byte source() {
      return ScopeSource.MANUAL.id();
    }

    @Override
    public Continuation capture() {
      return null;
    }

    @Override
    public Continuation captureConcurrent() {
      return null;
    }

    @Override
    public boolean checkpointed() {
      return false;
    }

    @Override
    public boolean isAsyncPropagating() {
      return false;
    }

    @Override
    public void setAsyncPropagation(final boolean value) {
      // discard setting
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o instanceof CustomScope) {
        return delegate.equals(((CustomScope) o).delegate);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }
  }
}
