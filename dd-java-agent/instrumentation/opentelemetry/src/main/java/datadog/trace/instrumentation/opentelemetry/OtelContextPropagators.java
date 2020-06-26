package datadog.trace.instrumentation.opentelemetry;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
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
      tracer.inject(converter.toAgentSpan(span), carrier, new OtelSetter<>(setter));
    }

    @Override
    public <C> Context extract(final Context context, final C carrier, final Getter<C> getter) {
      final AgentSpan.Context agentContext = tracer.extract(carrier, new OtelGetter<>(getter));
      return TracingContextUtils.withSpan(
          DefaultSpan.create(converter.toSpanContext(agentContext)), context);
    }
  }

  private static class OtelSetter<C> implements AgentPropagation.Setter<C> {
    private final HttpTextFormat.Setter<C> setter;

    private OtelSetter(final HttpTextFormat.Setter<C> setter) {
      this.setter = setter;
    }

    @Override
    public void set(final C carrier, final String key, final String value) {
      setter.set(carrier, key, value);
    }
  }

  private static class OtelGetter<C> extends CachingContextVisitor<C> {
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
    public void forEachKey(
        C carrier,
        AgentPropagation.KeyClassifier classifier,
        AgentPropagation.KeyValueConsumer consumer) {
      for (String key : KEYS) {
        String lowerCaseKey = toLowerCase(key);
        int classification = classifier.classify(lowerCaseKey);
        if (classification != IGNORE) {
          if (!consumer.accept(classification, lowerCaseKey, getter.get(carrier, key))) {
            return;
          }
        }
      }
    }
  }
}
