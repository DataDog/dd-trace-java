package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.newInjector;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class W3CHttpInjectorTest extends DDCoreJavaSpecification {

  private HttpCodec.Injector injector;

  @BeforeEach
  void setup() {
    Map<String, String> mapping = new LinkedHashMap<>();
    mapping.put("some-baggage-key", "SOME_CUSTOM_HEADER");
    injector = newInjector(mapping);
  }

  @TableTest({
    "scenario                | traceId              | spanId               | samplingPriority              | origin | tracestate                                    ",
    "unset 1->2              | 1                    | 2                    | PrioritySampling.UNSET        |        | 'dd=p:0000000000000002;t.usr:123'             ",
    "keep 1->4 saipan        | 1                    | 4                    | PrioritySampling.SAMPLER_KEEP | saipan | 'dd=s:1;o:saipan;p:0000000000000004;t.usr:123'",
    "unset max->max-1 saipan | 18446744073709551615 | 18446744073709551614 | PrioritySampling.UNSET        | saipan | 'dd=o:saipan;p:fffffffffffffffe;t.usr:123'    ",
    "keep max-1->max         | 18446744073709551614 | 18446744073709551615 | PrioritySampling.SAMPLER_KEEP |        | 'dd=s:1;p:ffffffffffffffff;t.usr:123'         ",
    "drop max-1->max         | 18446744073709551614 | 18446744073709551615 | PrioritySampling.SAMPLER_DROP |        | 'dd=s:0;p:ffffffffffffffff;t.usr:123'         "
  })
  void injectHttpHeaders(
      String traceId,
      String spanId,
      @ConvertWith(PrioritySamplingConverter.class) int samplingPriority,
      String origin,
      String tracestate) {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put("some-baggage-key", "some-value");
    DDSpanContext mockedContext =
        createContext(
            tracer,
            DDTraceId.from(traceId),
            DDSpanId.from(spanId),
            samplingPriority,
            origin,
            baggage,
            PropagationTags.factory()
                .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"));
    Map<String, String> carrier = new LinkedHashMap<>();
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(TRACE_PARENT_KEY, buildTraceParent(traceId, spanId, samplingPriority));
    expected.put(TRACE_STATE_KEY, tracestate);
    expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
    expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
    expected.put("SOME_CUSTOM_HEADER", "some-value");

    injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

    assertEquals(expected, carrier);

    tracer.close();
  }

  @Test
  void injectHttpHeadersWithEndToEnd() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext =
        createContext(
            tracer,
            DDTraceId.from("1"),
            DDSpanId.from("2"),
            PrioritySampling.UNSET,
            "fakeOrigin",
            baggage,
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"));
    mockedContext.beginEndToEnd();
    Map<String, String> carrier = new LinkedHashMap<>();

    injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(TRACE_PARENT_KEY, buildTraceParent("1", "2", PrioritySampling.UNSET));
    expected.put(TRACE_STATE_KEY, "dd=o:fakeOrigin;p:0000000000000002;t.dm:-4;t.anytag:value");
    expected.put(
        OT_BAGGAGE_PREFIX + "t0", String.valueOf(mockedContext.getEndToEndStartTime() / 1000000L));
    expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
    expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
    assertEquals(expected, carrier);

    tracer.close();
  }

  @Test
  void injectTheDecisionMakerTag() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext =
        createContext(
            tracer,
            DDTraceId.from("1"),
            DDSpanId.from("2"),
            PrioritySampling.UNSET,
            "fakeOrigin",
            baggage,
            PropagationTags.factory().empty());
    mockedContext.setSamplingPriority(PrioritySampling.USER_KEEP, MANUAL);
    Map<String, String> carrier = new LinkedHashMap<>();

    injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(TRACE_PARENT_KEY, buildTraceParent("1", "2", PrioritySampling.USER_KEEP));
    expected.put(TRACE_STATE_KEY, "dd=s:2;o:fakeOrigin;p:0000000000000002;t.dm:-4");
    expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
    expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
    assertEquals(expected, carrier);

    tracer.close();
  }

  @Test
  void updateLastParentIdOnChildSpan() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> carrier = new LinkedHashMap<>();

    // injecting root span context
    AgentSpan rootSpan = tracer.startSpan("test", "root");
    long rootSpanId = rootSpan.getSpanId();
    AgentScope rootScope = tracer.activateSpan(rootSpan);

    injector.inject((DDSpanContext) rootSpan.context(), carrier, MapSetter.INSTANCE);
    long lastParentId = extractLastParentId(carrier);

    // trace state has root span id as last parent
    assertEquals(rootSpanId, lastParentId);

    // injecting child span context
    AgentSpan childSpan = tracer.startSpan("test", "child");
    long childSpanId = childSpan.getSpanId();
    carrier.clear();
    injector.inject((DDSpanContext) childSpan.context(), carrier, MapSetter.INSTANCE);
    lastParentId = extractLastParentId(carrier);

    // trace state has child span id as last parent
    assertEquals(childSpanId, lastParentId);

    // injecting root span again
    childSpan.finish();
    carrier.clear();
    injector.inject((DDSpanContext) rootSpan.context(), carrier, MapSetter.INSTANCE);
    lastParentId = extractLastParentId(carrier);

    // trace state has root span is as last parent again
    assertEquals(rootSpanId, lastParentId);

    rootScope.close();
    rootSpan.finish();
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
    for (String member : traceState.split(",")) {
      if (member.startsWith("dd=")) {
        for (String part : member.substring(3).split(";")) {
          if (part.startsWith("p:")) {
            return DDSpanId.fromHex(part.substring(2));
          }
        }
      }
    }
    throw new AssertionError("No 'p:' in dd tracestate: " + traceState);
  }

  private static DDSpanContext createContext(
      CoreTracer tracer,
      DDTraceId traceId,
      long spanId,
      int samplingPriority,
      String origin,
      Map<String, String> baggage,
      PropagationTags propagationTags) {
    return new DDSpanContext(
        traceId,
        spanId,
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
        propagationTags);
  }
}
