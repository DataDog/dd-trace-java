package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;

// Centralized place to do conversions
class TypeConverter {
  private final LogHandler logHandler;
  private final OTSpan noopSpanWrapper;
  private final OTSpanContext noopContextWrapper;
  private final OTScopeManager.OTScope noopScopeWrapper;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
    noopSpanWrapper = new OTSpan(AgentTracer.NoopAgentSpan.INSTANCE, this, logHandler);
    noopContextWrapper = new OTSpanContext(AgentTracer.NoopContext.INSTANCE);
    noopScopeWrapper = new OTScopeManager.OTScope(AgentTracer.NoopAgentScope.INSTANCE, false, this);
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span == null) {
      return null;
    } else if (span instanceof OTSpan) {
      return ((OTSpan) span).asAgentSpan();
    } else {
      // NOOP Span
      return AgentTracer.NoopAgentSpan.INSTANCE;
    }
  }

  public OTSpan toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    if (agentSpan instanceof AttachableWrapper) {
      AttachableWrapper attachableSpanWrapper = (AttachableWrapper) agentSpan;
      Object wrapper = attachableSpanWrapper.getWrapper();
      if (wrapper instanceof OTSpan) {
        return (OTSpan) wrapper;
      }
      OTSpan spanWrapper = new OTSpan(agentSpan, this, logHandler);
      attachableSpanWrapper.attachWrapper(spanWrapper);
      return spanWrapper;
    }
    if (agentSpan == AgentTracer.NoopAgentSpan.INSTANCE) {
      return noopSpanWrapper;
    }
    return new OTSpan(agentSpan, this, logHandler);
  }

  public AgentScope toAgentScope(final Span span, final Scope scope) {
    // check both: with OT33 we could have an active span with a null scope
    // because the method to retrieve the active scope was removed in OT33
    if (span == null && scope == null) {
      return null;
    } else if (scope instanceof OTScopeManager.OTScope) {
      OTScopeManager.OTScope wrapper = (OTScopeManager.OTScope) scope;
      if (wrapper.isFinishSpanOnClose()) {
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
    if (scope instanceof AttachableWrapper) {
      AttachableWrapper attachableScopeWrapper = (AttachableWrapper) scope;
      Object wrapper = attachableScopeWrapper.getWrapper();
      if (wrapper instanceof OTScopeManager.OTScope) {
        OTScopeManager.OTScope attachedScopeWrapper = (OTScopeManager.OTScope) wrapper;
        if (attachedScopeWrapper.isFinishSpanOnClose() == finishSpanOnClose) {
          return (Scope) wrapper;
        }
      }
      Scope otScope = new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
      attachableScopeWrapper.attachWrapper(otScope);
      return otScope;
    }
    if (scope == AgentTracer.NoopAgentScope.INSTANCE) {
      return noopScopeWrapper;
    }
    return new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == AgentTracer.NoopContext.INSTANCE) {
      return noopContextWrapper;
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
    private final Scope scope; // may be null on OT33 as we can't retrieve the active scope

    private CustomScope(final Span span, final Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    @Override
    public void close() {
      if (scope != null) {
        scope.close();
      }
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
        CustomScope customScope = (CustomScope) o;
        if (scope != null && customScope.scope != null) {
          return scope.equals(customScope.scope);
        } else {
          return span.equals(customScope.span);
        }
      }
      return false;
    }

    @Override
    public int hashCode() {
      return scope != null ? scope.hashCode() : span.hashCode();
    }
  }
}
