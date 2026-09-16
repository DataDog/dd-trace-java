package datadog.trace.core.propagation;

import static datadog.trace.api.internal.util.LongStringUtils.toHexStringPadded;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.TraceIdConverter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class DatadogHttpInjectorTest extends AbstractHttpInjectorTest {

  @Override
  protected HttpCodec.Injector newInjector() {
    return DatadogHttpCodec.newInjector(singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));
  }

  @TableTest({
    "scenario          | traceId | spanId  | samplingPriority | origin  ",
    "unset no origin   | '1'     | '2'     | UNSET            |         ",
    "keep with origin  | '1'     | '2'     | SAMPLER_KEEP     | 'saipan'",
    "uint64 max unset  | 'MAX'   | 'MAX-1' | UNSET            | 'saipan'",
    "uint64 max-1 keep | 'MAX-1' | 'MAX'   | SAMPLER_KEEP     |         "
  })
  void injectHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put("some-baggage-key", "some-value");

    DDSpanContext spanContext =
        mockSpanContext(traceId, spanId, samplingPriority, origin, baggage, "_dd.p.usr=123");
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    int expectedSize = 6; // trace_id, span_id, k1, k2, custom_header, datadog_tags
    assertEquals(traceId, carrier.get(TRACE_ID_KEY));
    assertEquals(spanId, carrier.get(SPAN_ID_KEY));
    if (samplingPriority != UNSET) {
      assertEquals(String.valueOf(samplingPriority), carrier.get(SAMPLING_PRIORITY_KEY));
      expectedSize++;
    }
    if (origin != null) {
      assertEquals(origin, carrier.get(ORIGIN_KEY));
      expectedSize++;
    }
    assertEquals("v1", carrier.get(OT_BAGGAGE_PREFIX + "k1"));
    assertEquals("v2", carrier.get(OT_BAGGAGE_PREFIX + "k2"));
    assertEquals("some-value", carrier.get("SOME_CUSTOM_HEADER"));
    assertEquals("_dd.p.usr=123", carrier.get(DATADOG_TAGS_KEY));
    assertEquals(expectedSize, carrier.size());
  }

  @Test
  void injectHttpHeadersWithEndToEnd() {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");

    DDSpanContext spanContext =
        mockSpanContext("1", "2", UNSET, "fakeOrigin", baggage, "_dd.p.dm=-4,_dd.p.anytag=value");
    spanContext.beginEndToEnd();
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    String expectedT0 = String.valueOf(spanContext.getEndToEndStartTime() / 1_000_000L);
    assertEquals("1", carrier.get(TRACE_ID_KEY));
    assertEquals("2", carrier.get(SPAN_ID_KEY));
    assertEquals("fakeOrigin", carrier.get(ORIGIN_KEY));
    assertEquals(expectedT0, carrier.get(OT_BAGGAGE_PREFIX + "t0"));
    assertEquals("v1", carrier.get(OT_BAGGAGE_PREFIX + "k1"));
    assertEquals("v2", carrier.get(OT_BAGGAGE_PREFIX + "k2"));
    assertEquals("_dd.p.dm=-4,_dd.p.anytag=value", carrier.get(DATADOG_TAGS_KEY));
    assertEquals(7, carrier.size());
  }

  @Test
  void injectTheDecisionMakerTag() {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");

    DDSpanContext spanContext = mockSpanContext("1", "2", UNSET, "fakeOrigin", baggage, null);
    spanContext.setSamplingPriority(USER_KEEP, MANUAL);
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    assertEquals("1", carrier.get(TRACE_ID_KEY));
    assertEquals("2", carrier.get(SPAN_ID_KEY));
    assertEquals("fakeOrigin", carrier.get(ORIGIN_KEY));
    assertEquals("v1", carrier.get(OT_BAGGAGE_PREFIX + "k1"));
    assertEquals("v2", carrier.get(OT_BAGGAGE_PREFIX + "k2"));
    assertEquals("2", carrier.get("x-datadog-sampling-priority"));
    assertEquals("_dd.p.dm=-4", carrier.get(DATADOG_TAGS_KEY));
    assertEquals(7, carrier.size());
  }

  @TableTest({
    "scenario            | hexId                             ",
    "64-bit short        | '1'                               ",
    "64-bit max chars    | '123456789abcdef0'                ",
    "128-bit             | '123456789abcdef0123456789abcdef0'",
    "128-bit zero middle | '64184f2400000000123456789abcdef0'",
    "128-bit all f       | 'ffffffffffffffffffffffffffffffff'"
  })
  void injectHttpHeadersWith128BitTraceId(String hexId) {
    DD128bTraceId traceId = DD128bTraceId.fromHex(hexId);
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");

    DDSpanContext spanContext =
        mockSpanContext(traceId, "2", UNSET, null, baggage, "_dd.p.dm=-4,_dd.p.anytag=value");
    spanContext.beginEndToEnd();
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    String expectedT0 = String.valueOf(spanContext.getEndToEndStartTime() / 1_000_000L);
    String expectDdPTags =
        traceId.toHighOrderLong() == 0
            ? "_dd.p.dm=-4,_dd.p.anytag=value"
            : "_dd.p.dm=-4,_dd.p.tid="
                + toHexStringPadded(traceId.toHighOrderLong(), 16)
                + ",_dd.p.anytag=value";
    assertEquals(traceId.toString(), carrier.get(TRACE_ID_KEY));
    assertEquals("2", carrier.get(SPAN_ID_KEY));
    assertEquals(expectedT0, carrier.get(OT_BAGGAGE_PREFIX + "t0"));
    assertEquals("v1", carrier.get(OT_BAGGAGE_PREFIX + "k1"));
    assertEquals("v2", carrier.get(OT_BAGGAGE_PREFIX + "k2"));
    assertEquals(expectDdPTags, carrier.get(DATADOG_TAGS_KEY));
    assertEquals(6, carrier.size());
  }

  private DDSpanContext mockSpanContext(
      String traceId,
      String spanId,
      byte samplingPriority,
      String origin,
      Map<String, String> baggage,
      String ddPTags) {
    return mockSpanContext(
        DDTraceId.from(traceId), spanId, samplingPriority, origin, baggage, ddPTags);
  }

  private DDSpanContext mockSpanContext(
      DDTraceId traceId,
      String spanId,
      byte samplingPriority,
      String origin,
      Map<String, String> baggage,
      String ddPTags) {
    PropagationTags propagationTags =
        ddPTags == null
            ? PropagationTags.factory().empty()
            : PropagationTags.factory().fromHeaderValue(DATADOG, ddPTags);
    return mockSpanContext(
        traceId, DDSpanId.from(spanId), samplingPriority, origin, baggage, propagationTags);
  }
}
