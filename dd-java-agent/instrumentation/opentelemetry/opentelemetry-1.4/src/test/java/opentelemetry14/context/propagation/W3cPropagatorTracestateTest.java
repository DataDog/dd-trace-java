package opentelemetry14.context.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import opentelemetry14.AbstractOpenTelemetry14Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WithConfig(key = "trace.propagation.style", value = "tracecontext")
class W3cPropagatorTracestateTest extends AbstractOpenTelemetry14Test {
  @ParameterizedTest
  @ValueSource(strings = {"foo=1,bar=2", "dd=s:0,foo=1,bar=2", "foo=1,dd=s:0,bar=2"})
  void testTracestatePropagation(String tracestate) {
    TextMapPropagator propagator =
        GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator();
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "00-11111111111111111111111111111111-2222222222222222-00");
    headers.put("tracestate", tracestate);

    String[] members =
        Arrays.stream(tracestate.split(","))
            .filter(member -> !member.startsWith("dd="))
            .toArray(String[]::new);

    Context context = propagator.extract(Context.root(), headers, TextMap.INSTANCE);
    assertNotEquals(Context.root(), context);

    Span localSpan = this.otelTracer.spanBuilder("some-name").setParent(context).startSpan();
    Map<String, String> injectedHeaders = new HashMap<>();
    try (Scope ignoredScope = localSpan.makeCurrent()) {
      propagator.inject(Context.current(), injectedHeaders, TextMap.INSTANCE);
    }
    localSpan.end();

    // Check tracestate was injected
    String injectedTracestate = injectedHeaders.get("tracestate");
    assertNotNull(injectedTracestate);
    // Check tracestate contains extracted members plus the Datadog one in first position
    String[] injectedMembers = injectedTracestate.split(",");
    assertEquals(Math.min(1 + members.length, 32), injectedMembers.length);
    // Check datadog member (should be injected as first member)
    String expectedDdMember =
        "dd=s:0;p:" + localSpan.getSpanContext().getSpanId() + ";t.tid:1111111111111111";
    assertEquals(expectedDdMember, injectedMembers[0]);
    // Check all other members
    for (int i = 0; i < Math.min(members.length, 31); i++) {
      assertEquals(members[i], injectedMembers[i + 1]);
    }
  }
}
