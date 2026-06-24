package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.junit.utils.converter.TraceIdConverter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class W3CHttpInjectorTest extends AbstractHttpInjectorTest {

  @Override
  protected HttpCodec.Injector newInjector() {
    return W3CHttpCodec.newInjector(singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));
  }

  @TableTest({
    "scenario                | traceId | spanId | samplingPriority | origin | tracestate                                    ",
    "unset 1->2              | 1       | 2      | UNSET            |        | 'dd=p:0000000000000002;t.usr:123'             ",
    "keep 1->4 saipan        | 1       | 4      | SAMPLER_KEEP     | saipan | 'dd=s:1;o:saipan;p:0000000000000004;t.usr:123'",
    "unset max->max-1 saipan | MAX     | MAX-1  | UNSET            | saipan | 'dd=o:saipan;p:fffffffffffffffe;t.usr:123'    ",
    "keep max-1->max         | MAX-1   | MAX    | SAMPLER_KEEP     |        | 'dd=s:1;p:ffffffffffffffff;t.usr:123'         ",
    "drop max-1->max         | MAX-1   | MAX    | SAMPLER_DROP     |        | 'dd=s:0;p:ffffffffffffffff;t.usr:123'         "
  })
  void injectHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin,
      String tracestate) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put("some-baggage-key", "some-value");
    DDSpanContext spanContext =
        mockSpanContext(
            DDTraceId.from(traceId),
            DDSpanId.from(spanId),
            samplingPriority,
            origin,
            baggage,
            PropagationTags.factory().fromHeaderValue(DATADOG, "_dd.p.usr=123"));
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    Map<String, String> expected = new HashMap<>();
    expected.put(TRACE_PARENT_KEY, buildTraceParent(traceId, spanId, samplingPriority));
    expected.put(TRACE_STATE_KEY, tracestate);
    expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
    expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
    expected.put("SOME_CUSTOM_HEADER", "some-value");
    assertEquals(expected, carrier);
  }

  @Test
  void injectHttpHeadersWithEndToEnd() {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext =
        mockSpanContext(
            DDTraceId.from("1"),
            DDSpanId.from("2"),
            UNSET,
            "fakeOrigin",
            baggage,
            PropagationTags.factory().fromHeaderValue(DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"));
    mockedContext.beginEndToEnd();
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(mockedContext, carrier, Map::put);

    Map<String, String> expected = new HashMap<>();
    expected.put(TRACE_PARENT_KEY, buildTraceParent("1", "2", UNSET));
    expected.put(TRACE_STATE_KEY, "dd=o:fakeOrigin;p:0000000000000002;t.dm:-4;t.anytag:value");
    expected.put(
        OT_BAGGAGE_PREFIX + "t0", String.valueOf(mockedContext.getEndToEndStartTime() / 1000000L));
    expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
    expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
    assertEquals(expected, carrier);
  }

  @Test
  void injectTheDecisionMakerTag() {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext =
        mockSpanContext(
            DDTraceId.from("1"),
            DDSpanId.from("2"),
            UNSET,
            "fakeOrigin",
            baggage,
            PropagationTags.factory().empty());
    mockedContext.setSamplingPriority(USER_KEEP, MANUAL);
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(mockedContext, carrier, Map::put);

    Map<String, String> expected = new HashMap<>();
    expected.put(TRACE_PARENT_KEY, buildTraceParent("1", "2", USER_KEEP));
    expected.put(TRACE_STATE_KEY, "dd=s:2;o:fakeOrigin;p:0000000000000002;t.dm:-4");
    expected.put(OT_BAGGAGE_PREFIX + "k1", "v1");
    expected.put(OT_BAGGAGE_PREFIX + "k2", "v2");
    assertEquals(expected, carrier);
  }

  @Test
  void updateLastParentIdOnChildSpan() {
    Map<String, String> carrier = new HashMap<>();

    // injecting root span context
    AgentSpan rootSpan = this.tracer.startSpan("test", "root");
    long rootSpanId = rootSpan.getSpanId();
    AgentScope rootScope = this.tracer.activateSpan(rootSpan);

    this.injector.inject((DDSpanContext) rootSpan.spanContext(), carrier, Map::put);

    // trace state has root span id as last parent
    assertEquals(rootSpanId, extractLastParentId(carrier));

    // injecting child span context
    AgentSpan childSpan = this.tracer.startSpan("test", "child");
    long childSpanId = childSpan.getSpanId();
    carrier.clear();
    this.injector.inject((DDSpanContext) childSpan.spanContext(), carrier, Map::put);

    // trace state has child span id as last parent
    assertEquals(childSpanId, extractLastParentId(carrier));

    // injecting root span again
    childSpan.finish();
    carrier.clear();
    this.injector.inject((DDSpanContext) rootSpan.spanContext(), carrier, Map::put);

    // trace state has root span is as last parent again
    assertEquals(rootSpanId, extractLastParentId(carrier));

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
}
