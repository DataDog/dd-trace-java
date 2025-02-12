package datadog.trace.instrumentation.opentelemetry;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.grpc.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.HttpTextFormat;
import io.opentelemetry.trace.DefaultSpan;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.TracingContextUtils;
import java.util.Arrays;
import java.util.List;

public class OtelContextPropagators implements ContextPropagators {
  public static final OtelContextPropagators INSTANCE = new OtelContextPropagators();

  private OtelContextPropagators() {}

  @Override
  public HttpTextFormat getHttpTextFormat() {
    return OtelHttpTextFormat.INSTANCE;
  }

  private static class OtelHttpTextFormat implements HttpTextFormat {
    private static final OtelHttpTextFormat INSTANCE = new OtelHttpTextFormat();

    private final AgentTracer.TracerAPI tracer = AgentTracer.get();
    private final TypeConverter converter = new TypeConverter();

    @Override
    public List<String> fields() {
      return null;
    }

    @Override
    public <C> void inject(final Context context, final C carrier, final Setter<C> setter) {
      final Span span = TracingContextUtils.getSpanWithoutDefault(context);
      if (span == null || !span.getContext().isValid()) {
        return;
      }
      AgentSpan agentSpan = converter.toAgentSpan(span);
      if (agentSpan != null) {
        defaultPropagator().inject(agentSpan, carrier, setter::set);
      }
    }

    @Override
    public <C> Context extract(final Context context, final C carrier, final Getter<C> getter) {
      final AgentSpanContext agentContext =
          extractContextAndGetSpanContext(carrier, new OtelGetter<>(getter));
      return TracingContextUtils.withSpan(
          DefaultSpan.create(converter.toSpanContext(agentContext)), context);
    }
  }

  private static class OtelGetter<C> implements AgentPropagation.ContextVisitor<C> {
    private static final String DD_TRACE_ID_KEY = "x-datadog-trace-id";
    private static final String DD_SPAN_ID_KEY = "x-datadog-parent-id";
    private static final String DD_SAMPLING_PRIORITY_KEY = "x-datadog-sampling-priority";
    private static final String DD_ORIGIN_KEY = "x-datadog-origin";

    private static final String B3_TRACE_ID_KEY = "X-B3-TraceId";
    private static final String B3_SPAN_ID_KEY = "X-B3-SpanId";
    private static final String B3_SAMPLING_PRIORITY_KEY = "X-B3-Sampled";

    private static final String HAYSTACK_TRACE_ID_KEY = "Trace-ID";
    private static final String HAYSTACK_SPAN_ID_KEY = "Span-ID";
    private static final String HAYSTACK_PARENT_ID_KEY = "Parent_ID";

    private static final List<String> KEYS =
        Arrays.asList(
            DD_TRACE_ID_KEY,
            DD_SPAN_ID_KEY,
            DD_SAMPLING_PRIORITY_KEY,
            DD_ORIGIN_KEY,
            B3_TRACE_ID_KEY,
            B3_SPAN_ID_KEY,
            B3_SAMPLING_PRIORITY_KEY,
            HAYSTACK_TRACE_ID_KEY,
            HAYSTACK_SPAN_ID_KEY,
            HAYSTACK_PARENT_ID_KEY);

    private final HttpTextFormat.Getter<C> getter;

    private OtelGetter(final HttpTextFormat.Getter<C> getter) {
      this.getter = getter;
    }

    @Override
    public void forEachKey(C carrier, AgentPropagation.KeyClassifier classifier) {
      // TODO: Otel doesn't expose the keys, so we have to rely on hard coded keys.
      // https://github.com/open-telemetry/opentelemetry-specification/issues/433
      for (String key : KEYS) {
        if (!classifier.accept(key, getter.get(carrier, key))) {
          return;
        }
      }
    }
  }
}
