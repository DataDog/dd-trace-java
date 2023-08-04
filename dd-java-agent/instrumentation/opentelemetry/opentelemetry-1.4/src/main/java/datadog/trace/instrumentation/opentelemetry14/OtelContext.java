package datadog.trace.instrumentation.opentelemetry14;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelContext implements Context {
  private static final String OTEL_CONTEXT_SPAN_KEY = "opentelemetry-trace-span-key";
  private static final String DATADOG_CONTEXT_ROOT_SPAN_KEY = "datadog-root-span-key";

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
    } else if (DATADOG_CONTEXT_ROOT_SPAN_KEY.equals(key.toString())) {
      return (V) this.rootSpan;
    }
    return null;
  }

  @Override
  public <V> Context with(ContextKey<V> k1, V v1) {
    if (OTEL_CONTEXT_SPAN_KEY.equals(k1.toString())) {
      return new OtelContext((Span) v1, this.rootSpan);
    } else if (DATADOG_CONTEXT_ROOT_SPAN_KEY.equals(k1.toString())) {
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
}
