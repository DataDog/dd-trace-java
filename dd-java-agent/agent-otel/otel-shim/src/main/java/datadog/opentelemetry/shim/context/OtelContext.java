package datadog.opentelemetry.shim.context;

import datadog.opentelemetry.shim.trace.OtelSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelContext implements Context {
  /** Overridden root context. */
  public static final OtelContext ROOT = new OtelContext(OtelSpan.invalid(), OtelSpan.invalid());

  private static final String OTEL_CONTEXT_SPAN_KEY = "opentelemetry-trace-span-key";
  private static final String OTEL_CONTEXT_ROOT_SPAN_KEY = "opentelemetry-traces-local-root-span";

  private final Span currentSpan;
  private final Span rootSpan;

  public OtelContext(Span currentSpan, Span rootSpan) {
    this.currentSpan = currentSpan;
    this.rootSpan = rootSpan;
  }

  @Nullable
  @Override
  public <V> V get(ContextKey<V> key) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(key.toString())) {
      return (V) this.currentSpan;
    } else if (OTEL_CONTEXT_ROOT_SPAN_KEY.equals(key.toString())) {
      return (V) this.rootSpan;
    }
    return null;
  }

  @Override
  public <V> Context with(ContextKey<V> k1, V v1) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(k1.toString())) {
      return new OtelContext((Span) v1, this.rootSpan);
    } else if (OTEL_CONTEXT_ROOT_SPAN_KEY.equals(k1.toString())) {
      return new OtelContext(this.currentSpan, (Span) v1);
    }
    return this;
  }

  @Override
  public Scope makeCurrent() {
    Scope scope = Context.super.makeCurrent();
    if (this.currentSpan instanceof OtelSpan) {
      AgentScope agentScope = ((OtelSpan) this.currentSpan).activate();
      scope = new OtelScope(scope, agentScope);
    }
    return scope;
  }

  public static Context current() {
    // Check empty context
    AgentSpan agentCurrentSpan = AgentTracer.activeSpan();
    if (null == agentCurrentSpan) {
      return OtelContext.ROOT;
    }
    // Get OTel current span
    Span otelCurrentSpan = null;
    if (agentCurrentSpan instanceof AttachableWrapper) {
      Object wrapper = ((AttachableWrapper) agentCurrentSpan).getWrapper();
      if (wrapper instanceof OtelSpan) {
        otelCurrentSpan = (OtelSpan) wrapper;
      }
    }
    if (otelCurrentSpan == null) {
      otelCurrentSpan = new OtelSpan(agentCurrentSpan);
    }
    // Get OTel root span
    Span otelRootSpan = null;
    AgentSpan agentRootSpan = agentCurrentSpan.getLocalRootSpan();
    if (agentRootSpan instanceof AttachableWrapper) {
      Object wrapper = ((AttachableWrapper) agentRootSpan).getWrapper();
      if (wrapper instanceof OtelSpan) {
        otelRootSpan = (OtelSpan) wrapper;
      }
    }
    if (otelRootSpan == null) {
      otelRootSpan = new OtelSpan(agentRootSpan);
    }
    return new OtelContext(otelCurrentSpan, otelRootSpan);
  }

  @Override
  public String toString() {
    return "OtelContext{"
        + "currentSpan="
        + this.currentSpan.getSpanContext()
        + ", rootSpan="
        + this.rootSpan.getSpanContext()
        + '}';
  }
}
