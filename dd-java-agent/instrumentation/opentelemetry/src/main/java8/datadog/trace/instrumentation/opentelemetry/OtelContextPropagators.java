package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.List;

public final class OtelContextPropagators implements ContextPropagators {

  private final OtelTextMapPropagator propagator;

  public OtelContextPropagators(ContextStore<SpanContext, AgentSpan.Context> spanContextStore) {
    propagator = new OtelTextMapPropagator(spanContextStore);
  }

  @Override
  public TextMapPropagator getTextMapPropagator() {
    return propagator;
  }

  private static class OtelTextMapPropagator implements TextMapPropagator {

    private final AgentTracer.TracerAPI tracer = AgentTracer.get();
    private final TypeConverter converter;

    private OtelTextMapPropagator(ContextStore<SpanContext, AgentSpan.Context> spanContextStore) {
      converter = new TypeConverter(spanContextStore);
    }

    @Override
    public List<String> fields() {
      return null;
    }

    @Override
    public <C> void inject(final Context context, final C carrier, final Setter<C> setter) {
      final Span span = Span.fromContextOrNull(context);
      if (span == null || !span.getSpanContext().isValid()) {
        return;
      }
      tracer.inject(converter.toAgentSpan(span), carrier, new OtelSetter<>(setter));
    }

    @Override
    public <C> Context extract(final Context context, final C carrier, final Getter<C> getter) {
      final AgentSpan.Context agentContext = tracer.extract(carrier, new OtelGetter<>(getter));
      return Span.wrap(converter.toSpanContext(agentContext)).storeInContext(context);
    }
  }

  private static class OtelSetter<C> implements AgentPropagation.Setter<C> {
    private final TextMapPropagator.Setter<C> setter;

    private OtelSetter(final TextMapPropagator.Setter<C> setter) {
      this.setter = setter;
    }

    @Override
    public void set(final C carrier, final String key, final String value) {
      setter.set(carrier, key, value);
    }
  }

  private static class OtelGetter<C> implements AgentPropagation.ContextVisitor<C> {
    private final TextMapPropagator.Getter<C> getter;

    private OtelGetter(final TextMapPropagator.Getter<C> getter) {
      this.getter = getter;
    }

    @Override
    public void forEachKey(C carrier, AgentPropagation.KeyClassifier classifier) {
      for (String key : getter.keys(carrier)) {
        if (!classifier.accept(key, getter.get(carrier, key))) {
          return;
        }
      }
    }
  }
}
