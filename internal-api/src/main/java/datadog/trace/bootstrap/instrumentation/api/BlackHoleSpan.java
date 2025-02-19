package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;

/** An {@link AgentSpan} implementation that stops context propagation. */
public final class BlackHoleSpan extends NoopSpan {
  private final DDTraceId traceId;

  public BlackHoleSpan(final DDTraceId traceId) {
    this.traceId = traceId;
  }

  @Override
  public boolean isSameTrace(final AgentSpan otherSpan) {
    return otherSpan != null
        && ((traceId != null && traceId.equals(otherSpan.getTraceId()))
            || otherSpan.getTraceId() == null);
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public AgentSpanContext context() {
    return Context.INSTANCE;
  }

  public static final class Context extends NoopSpanContext {
    public static final Context INSTANCE = new Context();

    private Context() {}
  }
}
