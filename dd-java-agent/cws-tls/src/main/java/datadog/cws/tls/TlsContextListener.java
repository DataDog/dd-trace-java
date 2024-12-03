package datadog.cws.tls;

import static datadog.trace.api.tracing.ContextKeys.SPAN_CONTEXT_KEY;

import datadog.context.Context;
import datadog.context.ContextListener;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class TlsContextListener implements ContextListener {
  private final Tls tls;
  private final ThreadLocal<AgentSpan> activeSpans;

  public TlsContextListener() {
    this(TlsFactory.newTls(4096));
  }

  public TlsContextListener(Tls tls) {
    this.tls = tls;
    this.activeSpans = new ThreadLocal<>();
  }

  @Override
  public void onAttached(Context previous, Context currentContext) {
    AgentSpan agentSpan = currentContext.get(SPAN_CONTEXT_KEY);
    // If no active span
    if (agentSpan == null) {
      this.activeSpans.remove();
      this.tls.registerSpan(DD128bTraceId.ZERO, DDSpanId.ZERO);
    }
    // If new active span
    else if (this.activeSpans.get() != agentSpan) {
      this.activeSpans.set(agentSpan);
      this.tls.registerSpan(agentSpan.getTraceId(), agentSpan.getSpanId());
    }
  }
}
