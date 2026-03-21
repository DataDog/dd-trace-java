package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.newInjector;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class W3CHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector;

  @BeforeEach
  void setup() {
    injector = newInjector(Collections.singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));
  }

  static Stream<Arguments> injectHttpHeadersArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of("1", "2", UNSET, null, "dd=p:0000000000000002;t.usr:123"),
        Arguments.of(
            "1", "4", SAMPLER_KEEP, "saipan", "dd=s:1;o:saipan;p:0000000000000004;t.usr:123"),
        Arguments.of(
            maxStr, maxMinus1Str, UNSET, "saipan", "dd=o:saipan;p:fffffffffffffffe;t.usr:123"),
        Arguments.of(
            maxMinus1Str, maxStr, SAMPLER_KEEP, null, "dd=s:1;p:ffffffffffffffff;t.usr:123"),
        Arguments.of(
            maxMinus1Str, maxStr, SAMPLER_DROP, null, "dd=s:0;p:ffffffffffffffff;t.usr:123"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersArguments")
  void injectHttpHeaders(
      String traceId, String spanId, int samplingPriority, String origin, String tracestate)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k1", "v1");
      baggage.put("k2", "v2");
      baggage.put("some-baggage-key", "some-value");
      DDSpanContext mockedContext =
          new DDSpanContext(
              DDTraceId.from(traceId),
              DDSpanId.from(spanId),
              DDSpanId.ZERO,
              null,
              "fakeService",
              "fakeOperation",
              "fakeResource",
              samplingPriority,
              origin,
              baggage,
              false,
              "fakeType",
              0,
              tracer.createTraceCollector(DDTraceId.ONE),
              null,
              null,
              NoopPathwayContext.INSTANCE,
              false,
              PropagationTags.factory()
                  .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"));

      Map<String, String> carrier = new HashMap<>();
      Map<String, String> expected = new HashMap<>();
      expected.put(TRACE_PARENT_KEY, buildTraceParent(traceId, spanId, samplingPriority));
      expected.put(TRACE_STATE_KEY, tracestate);
      expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
      expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
      expected.put("SOME_CUSTOM_HEADER", "some-value");

      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      assertEquals(expected, carrier);
    } finally {
      tracer.close();
    }
  }

  @Test
  void injectHttpHeadersWithEndToEnd() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k1", "v1");
      baggage.put("k2", "v2");
      DDSpanContext mockedContext =
          new DDSpanContext(
              DDTraceId.from("1"),
              DDSpanId.from("2"),
              DDSpanId.ZERO,
              null,
              "fakeService",
              "fakeOperation",
              "fakeResource",
              UNSET,
              "fakeOrigin",
              baggage,
              false,
              "fakeType",
              0,
              tracer.createTraceCollector(DDTraceId.ONE),
              null,
              null,
              NoopPathwayContext.INSTANCE,
              false,
              PropagationTags.factory()
                  .fromHeaderValue(
                      PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"));

      mockedContext.beginEndToEnd();

      Map<String, String> carrier = new HashMap<>();
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      Map<String, String> expected = new HashMap<>();
      expected.put(TRACE_PARENT_KEY, buildTraceParent("1", "2", UNSET));
      expected.put(TRACE_STATE_KEY, "dd=o:fakeOrigin;p:0000000000000002;t.dm:-4;t.anytag:value");
      expected.put(
          OT_BAGGAGE_PREFIX + "t0",
          String.valueOf((long) (mockedContext.getEndToEndStartTime() / 1000000L)));
      expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
      expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
      assertEquals(expected, carrier);
    } finally {
      tracer.close();
    }
  }

  @Test
  void injectTheDecisionMakerTag() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k1", "v1");
      baggage.put("k2", "v2");
      DDSpanContext mockedContext =
          new DDSpanContext(
              DDTraceId.from("1"),
              DDSpanId.from("2"),
              DDSpanId.ZERO,
              null,
              "fakeService",
              "fakeOperation",
              "fakeResource",
              UNSET,
              "fakeOrigin",
              baggage,
              false,
              "fakeType",
              0,
              tracer.createTraceCollector(DDTraceId.ONE),
              null,
              null,
              NoopPathwayContext.INSTANCE,
              false,
              PropagationTags.factory().empty());

      mockedContext.setSamplingPriority(USER_KEEP, MANUAL);

      Map<String, String> carrier = new HashMap<>();
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      Map<String, String> expected = new HashMap<>();
      expected.put(TRACE_PARENT_KEY, buildTraceParent("1", "2", USER_KEEP));
      expected.put(TRACE_STATE_KEY, "dd=s:2;o:fakeOrigin;p:0000000000000002;t.dm:-4");
      expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
      expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
      assertEquals(expected, carrier);
    } finally {
      tracer.close();
    }
  }

  @Test
  void updateLastParentIdOnChildSpan() throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, String> carrier = new HashMap<>();

      // Injecting root span context
      AgentSpan rootSpan = tracer.startSpan("test", "root");
      long rootSpanId = rootSpan.getSpanId();
      AgentScope rootScope = tracer.activateSpan(rootSpan);

      injector.inject(
          rootSpan.context() instanceof DDSpanContext ? (DDSpanContext) rootSpan.context() : null,
          carrier,
          MapSetter.INSTANCE);
      long lastParentId = extractLastParentId(carrier);

      // trace state has root span id as last parent
      assertEquals(rootSpanId, lastParentId);

      // Injecting child span context
      AgentSpan childSpan = tracer.startSpan("test", "child");
      long childSpanId = childSpan.getSpanId();
      carrier.clear();
      injector.inject(
          childSpan.context() instanceof DDSpanContext ? (DDSpanContext) childSpan.context() : null,
          carrier,
          MapSetter.INSTANCE);
      lastParentId = extractLastParentId(carrier);

      // trace state has child span id as last parent
      assertEquals(childSpanId, lastParentId);

      // Injecting root span again
      childSpan.finish();
      carrier.clear();
      injector.inject(
          rootSpan.context() instanceof DDSpanContext ? (DDSpanContext) rootSpan.context() : null,
          carrier,
          MapSetter.INSTANCE);
      lastParentId = extractLastParentId(carrier);

      // trace state has root span id as last parent again
      assertEquals(rootSpanId, lastParentId);

      rootScope.close();
      rootSpan.finish();
    } finally {
      tracer.close();
    }
  }

  static String buildTraceParent(String traceId, String spanId, int samplingPriority) {
    return "00-"
        + DDTraceId.from(traceId).toHexString()
        + "-"
        + DDSpanId.toHexStringPadded(DDSpanId.from(spanId))
        + "-"
        + (samplingPriority > 0 ? "01" : "00");
  }

  static long extractLastParentId(Map<String, String> carrier) {
    String traceState = carrier.get(TRACE_STATE_KEY);
    String[] traceStateMembers = traceState.split(",");
    String ddTraceStateMember = null;
    for (String member : traceStateMembers) {
      if (member.startsWith("dd=")) {
        ddTraceStateMember = member.substring(3);
        break;
      }
    }
    assertNotNull(ddTraceStateMember);
    String[] parts = ddTraceStateMember.split(";");
    String spanIdHex = null;
    for (String part : parts) {
      if (part.startsWith("p:")) {
        spanIdHex = part.substring(2);
        break;
      }
    }
    assertNotNull(spanIdHex);
    return DDSpanId.fromHex(spanIdHex);
  }
}
