package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_PARENT_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_SPAN_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.tabletest.junit.TableTest;

class HaystackHttpInjectorTest extends DDCoreJavaSpecification {

  private static final CarrierSetter<Map<String, String>> MAP_SETTER = Map::put;

  private final HttpCodec.Injector injector =
      HaystackHttpCodec.newInjector(singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));

  @TableTest({
    "scenario            | traceId                | spanId                 | traceUuid                              | spanUuid                              ",
    "small ids           | '1'                    | '2'                    | '44617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "small ids duplicate | '1'                    | '2'                    | '44617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "uint64 max trace    | '18446744073709551615' | '18446744073709551614' | '44617461-646f-6721-ffff-ffffffffffff' | '44617461-646f-6721-ffff-fffffffffffe'",
    "uint64 max-1 trace  | '18446744073709551614' | '18446744073709551615' | '44617461-646f-6721-ffff-fffffffffffe' | '44617461-646f-6721-ffff-ffffffffffff'"
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeaders(String traceId, String spanId, String traceUuid, String spanUuid) {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
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
            SAMPLER_KEEP,
            null,
            baggage,
            false,
            "fakeType",
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, traceUuid);
    assertEquals(traceUuid, mockedContext.getTags().get(HAYSTACK_TRACE_ID_BAGGAGE_KEY));
    verify(carrier).put(DD_TRACE_ID_BAGGAGE_KEY, traceId);
    verify(carrier).put(SPAN_ID_KEY, spanUuid);
    verify(carrier).put(DD_SPAN_ID_BAGGAGE_KEY, spanId);
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k1", "v1");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    verify(carrier).put("SOME_CUSTOM_HEADER", "some-value");
    verify(carrier).put(DD_PARENT_ID_BAGGAGE_KEY, "0");
    verifyNoMoreInteractions(carrier);

    tracer.close();
  }

  @TableTest({
    "scenario            | traceId                | spanId                 | traceUuid                              | spanUuid                              ",
    "small ids           | '1'                    | '2'                    | '54617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "small ids duplicate | '1'                    | '2'                    | '54617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "uint64 max trace    | '18446744073709551615' | '18446744073709551614' | '54617461-646f-6721-ffff-ffffffffffff' | '44617461-646f-6721-ffff-fffffffffffe'",
    "uint64 max-1 trace  | '18446744073709551614' | '18446744073709551615' | '54617461-646f-6721-ffff-fffffffffffe' | '44617461-646f-6721-ffff-ffffffffffff'"
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeadersWithHaystackTraceIdInBaggage(
      String traceId, String spanId, String traceUuid, String spanUuid) {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, traceUuid);
    DDSpanContext mockedContext =
        new DDSpanContext(
            DDTraceId.from(traceId),
            DDSpanId.from(spanId),
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            SAMPLER_KEEP,
            null,
            baggage,
            false,
            "fakeType",
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, traceUuid);
    verify(carrier).put(DD_TRACE_ID_BAGGAGE_KEY, traceId);
    verify(carrier).put(SPAN_ID_KEY, spanUuid);
    verify(carrier).put(DD_SPAN_ID_BAGGAGE_KEY, spanId);
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k1", "v1");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    verifyNoMoreInteractions(carrier);

    tracer.close();
  }
}
