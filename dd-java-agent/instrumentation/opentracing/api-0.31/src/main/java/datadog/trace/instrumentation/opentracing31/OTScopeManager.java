package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;

public class OTScopeManager implements ScopeManager {
  private final TypeConverter converter;
  private final AgentTracer.TracerAPI tracer;

  public OTScopeManager(final AgentTracer.TracerAPI tracer, final TypeConverter converter) {
    this.tracer = tracer;
    this.converter = converter;
  }

  @Override
  public Scope activate(final Span span, final boolean finishSpanOnClose) {
    if (null == span) {
      return null;
    }

    final AgentSpan agentSpan = converter.toAgentSpan(span);
    final AgentScope agentScope = tracer.activateSpan(agentSpan, ScopeSource.MANUAL);

    return converter.toScope(agentScope, finishSpanOnClose);
  }

  @Deprecated
  @Override
  public Scope active() {
    // WARNING... Making an assumption about finishSpanOnClose
    return converter.toScope(tracer.activeScope(), false);
  }

  static class OTScope implements Scope, TraceScope {
    private final AgentScope delegate;
    private final boolean finishSpanOnClose;
    private final TypeConverter converter;

    OTScope(
        final AgentScope delegate, final boolean finishSpanOnClose, final TypeConverter converter) {
      this.delegate = delegate;
      this.finishSpanOnClose = finishSpanOnClose;
      this.converter = converter;
    }

    @Override
    public void close() {
      delegate.close();

      if (finishSpanOnClose) {
        delegate.span().finish();
      }
    }

    @Override
    public Span span() {
      return converter.toSpan(delegate.span());
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

    public boolean isFinishSpanOnClose() {
      return finishSpanOnClose;
    }
  }
}
