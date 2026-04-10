package opentelemetry14.context.propagation;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDTraceId.*;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.api.DDSpanId;
import datadog.trace.core.DDSpan;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.HashMap;
import java.util.Map;
import opentelemetry14.AbstractOpenTelemetry14Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public abstract class AbstractPropagatorTest extends AbstractOpenTelemetry14Test {

  abstract TextMapPropagator propagator();

  abstract void assertInjectedHeaders(
      Map<String, String> headers, String traceId, String spanId, byte sampling);

  @ParameterizedTest
  @MethodSource("values")
  void testContextExtractionAndInjection(
      Map<String, String> headers, String traceId, String spanId, byte sampling) {
    TextMapPropagator propagator = propagator();
    boolean expectedSampled = sampling == SAMPLER_KEEP;

    Context context = propagator.extract(Context.root(), headers, TextMap.INSTANCE);
    assertNotEquals(Context.root(), context);

    Span localSpan = this.otelTracer.spanBuilder("some-name").setParent(context).startSpan();
    String localSpanId = localSpan.getSpanContext().getSpanId();
    boolean spanSampled = localSpan.getSpanContext().getTraceFlags().isSampled();
    Map<String, String> injectedHeaders = new HashMap<>();
    try (Scope ignoredScope = localSpan.makeCurrent()) {
      propagator.inject(Context.current(), injectedHeaders, new TextMap());
    }
    localSpan.end();

    assertTraces(trace(span().operationName("internal").resourceName("some-name")));

    DDSpan ddSpan = writer.firstTrace().get(0);
    assertEquals(expectedTraceId(traceId), ddSpan.getTraceId().toString());
    assertEquals(DDSpanId.fromHex(spanId), ddSpan.getParentId());
    assertEquals(expectedSampled, spanSampled);
    assertInjectedHeaders(injectedHeaders, traceId, localSpanId, sampling);
  }

  String expectedTraceId(String traceId) {
    return fromHex(traceId).toString();
  }

  static String zeroPadLeft(String value, int length) {
    if (value.length() >= length) {
      return value;
    }
    StringBuilder sb = new StringBuilder(length);
    for (int i = value.length(); i < length; i++) {
      sb.append('0');
    }
    sb.append(value);
    return sb.toString();
  }

  static Map<String, String> headers(String... keyValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }
}
