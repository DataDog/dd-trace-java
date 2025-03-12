package datadog.trace.instrumentation.opentracing32;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OTScopeManager implements ScopeManager {
  static final Logger log = LoggerFactory.getLogger(OTScopeManager.class);

  private final TypeConverter converter;
  private final AgentTracer.TracerAPI tracer;

  public OTScopeManager(final AgentTracer.TracerAPI tracer, final TypeConverter converter) {
    this.tracer = tracer;
    this.converter = converter;
  }

  @Override
  public Scope activate(final Span span) {
    return activate(span, false);
  }

  @Override
  public Scope activate(final Span span, final boolean finishSpanOnClose) {
    if (null == span) {
      return null;
    }

    final AgentSpan agentSpan = converter.toAgentSpan(span);
    final AgentScope agentScope = tracer.activateManualSpan(agentSpan);

    return converter.toScope(agentScope, finishSpanOnClose);
  }

  @Deprecated
  @Override
  public Scope active() {
    AgentSpan agentSpan = tracer.activeSpan();
    if (null == agentSpan) {
      return null;
    }
    // WARNING... Making an assumption about finishSpanOnClose
    return new OTScope(new FakeScope(agentSpan), false, converter);
  }

  @Override
  public Span activeSpan() {
    return converter.toSpan(tracer.activeSpan());
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

    public boolean isFinishSpanOnClose() {
      return finishSpanOnClose;
    }
  }

  private final class FakeScope implements AgentScope {
    private final AgentSpan agentSpan;

    FakeScope(AgentSpan agentSpan) {
      this.agentSpan = agentSpan;
    }

    @Override
    public AgentSpan span() {
      return agentSpan;
    }

    @Override
    public void close() {
      if (agentSpan == tracer.activeSpan()) {
        tracer.closeActive();
      } else if (Config.get().isScopeStrictMode()) {
        throw new RuntimeException("Tried to close " + agentSpan + " scope when not on top");
      } else {
        log.warn("Tried to close {} scope when not on top", agentSpan);
      }
    }
  }
}
