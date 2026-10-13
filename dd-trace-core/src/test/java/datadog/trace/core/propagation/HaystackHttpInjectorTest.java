package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_PARENT_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_SPAN_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.HaystackHttpCodec.PARENT_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.junit.utils.converter.TraceIdConverter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class HaystackHttpInjectorTest extends AbstractHttpInjectorTest {
  // UUID representation of DDSpanId.ZERO
  private static final String ZERO_UUID = "44617461-646f-6721-0000-000000000000";

  @Override
  protected HttpCodec.Injector newInjector() {
    return HaystackHttpCodec.newInjector(singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));
  }

  @TableTest({
    "scenario            | traceId | spanId  | traceUuid                              | spanUuid                              ",
    "small ids           | '1'     | '2'     | '44617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "small ids duplicate | '1'     | '2'     | '44617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "uint64 max trace    | 'MAX'   | 'MAX-1' | '44617461-646f-6721-ffff-ffffffffffff' | '44617461-646f-6721-ffff-fffffffffffe'",
    "uint64 max-1 trace  | 'MAX-1' | 'MAX'   | '44617461-646f-6721-ffff-fffffffffffe' | '44617461-646f-6721-ffff-ffffffffffff'"
  })
  void injectHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      String traceUuid,
      String spanUuid) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put("some-baggage-key", "some-value");
    DDSpanContext spanContext = mockSpanContext(traceId, spanId, baggage);
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    assertEquals(traceUuid, carrier.get(TRACE_ID_KEY));
    assertEquals(traceUuid, spanContext.unsafeGetTag(HAYSTACK_TRACE_ID_BAGGAGE_KEY));
    assertEquals(traceId, carrier.get(DD_TRACE_ID_BAGGAGE_KEY));
    assertEquals(spanUuid, carrier.get(SPAN_ID_KEY));
    assertEquals(spanId, carrier.get(DD_SPAN_ID_BAGGAGE_KEY));
    assertEquals(ZERO_UUID, carrier.get(PARENT_ID_KEY));
    assertEquals("0", carrier.get(DD_PARENT_ID_BAGGAGE_KEY));
    assertEquals("v1", carrier.get(OT_BAGGAGE_PREFIX + "k1"));
    assertEquals("v2", carrier.get(OT_BAGGAGE_PREFIX + "k2"));
    assertEquals("some-value", carrier.get("SOME_CUSTOM_HEADER"));
    assertEquals(9, carrier.size());
  }

  @TableTest({
    "scenario            | traceId | spanId  | traceUuid                              | spanUuid                              ",
    "small ids           | '1'     | '2'     | '54617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "small ids duplicate | '1'     | '2'     | '54617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "uint64 max trace    | 'MAX'   | 'MAX-1' | '54617461-646f-6721-ffff-ffffffffffff' | '44617461-646f-6721-ffff-fffffffffffe'",
    "uint64 max-1 trace  | 'MAX-1' | 'MAX'   | '54617461-646f-6721-ffff-fffffffffffe' | '44617461-646f-6721-ffff-ffffffffffff'"
  })
  void injectHttpHeadersWithHaystackTraceIdInBaggage(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      String traceUuid,
      String spanUuid) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, traceUuid);
    DDSpanContext spanContext = mockSpanContext(traceId, spanId, baggage);
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    assertEquals(traceUuid, carrier.get(TRACE_ID_KEY));
    assertEquals(traceUuid, spanContext.unsafeGetTag(HAYSTACK_TRACE_ID_BAGGAGE_KEY));
    assertEquals(traceId, carrier.get(DD_TRACE_ID_BAGGAGE_KEY));
    assertEquals(spanUuid, carrier.get(SPAN_ID_KEY));
    assertEquals(spanId, carrier.get(DD_SPAN_ID_BAGGAGE_KEY));
    assertEquals(ZERO_UUID, carrier.get(PARENT_ID_KEY));
    assertEquals("0", carrier.get(DD_PARENT_ID_BAGGAGE_KEY));
    assertEquals("v1", carrier.get(OT_BAGGAGE_PREFIX + "k1"));
    assertEquals("v2", carrier.get(OT_BAGGAGE_PREFIX + "k2"));
    assertEquals(traceUuid, carrier.get(OT_BAGGAGE_PREFIX + "Haystack-Trace-ID"));
    assertEquals(9, carrier.size());
  }

  private DDSpanContext mockSpanContext(
      String traceId, String spanId, Map<String, String> baggage) {
    return mockSpanContext(
        DDTraceId.from(traceId), DDSpanId.from(spanId), SAMPLER_KEEP, null, baggage, null);
  }
}
