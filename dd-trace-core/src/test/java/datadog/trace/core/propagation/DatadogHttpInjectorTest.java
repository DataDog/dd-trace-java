package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static java.util.Collections.singletonMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class DatadogHttpInjectorTest extends DDCoreJavaSpecification {

  private static final CarrierSetter<Map<String, String>> MAP_SETTER = Map::put;

  private final HttpCodec.Injector injector =
      DatadogHttpCodec.newInjector(singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));

  @TableTest({
    "scenario          | traceId                | spanId                 | samplingPriority              | origin  ",
    "unset no origin   | '1'                    | '2'                    | PrioritySampling.UNSET        |         ",
    "keep with origin  | '1'                    | '2'                    | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "uint64 max unset  | '18446744073709551615' | '18446744073709551614' | PrioritySampling.UNSET        | 'saipan'",
    "uint64 max-1 keep | '18446744073709551614' | '18446744073709551615' | PrioritySampling.SAMPLER_KEEP |         "
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeaders(
      String traceId,
      String spanId,
      @ConvertWith(PrioritySamplingConverter.class) int samplingPriority,
      String origin) {
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
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, traceId);
    verify(carrier).put(SPAN_ID_KEY, spanId);
    if (samplingPriority != UNSET) {
      verify(carrier).put(SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority));
    }
    if (origin != null) {
      verify(carrier).put(ORIGIN_KEY, origin);
    }
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k1", "v1");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    verify(carrier).put("SOME_CUSTOM_HEADER", "some-value");
    verify(carrier).put(DATADOG_TAGS_KEY, "_dd.p.usr=123");
    verifyNoMoreInteractions(carrier);
  }

  @Test
  @SuppressWarnings("unchecked")
  void injectHttpHeadersWithEndToEnd() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
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
    String expectedT0 = String.valueOf(mockedContext.getEndToEndStartTime() / 1_000_000L);
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, "1");
    verify(carrier).put(SPAN_ID_KEY, "2");
    verify(carrier).put(ORIGIN_KEY, "fakeOrigin");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "t0", expectedT0);
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k1", "v1");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    verify(carrier).put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.anytag=value");
    verifyNoMoreInteractions(carrier);
  }

  @Test
  @SuppressWarnings("unchecked")
  void injectTheDecisionMakerTag() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
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
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, "1");
    verify(carrier).put(SPAN_ID_KEY, "2");
    verify(carrier).put(ORIGIN_KEY, "fakeOrigin");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k1", "v1");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    verify(carrier).put("x-datadog-sampling-priority", "2");
    verify(carrier).put(DATADOG_TAGS_KEY, "_dd.p.dm=-4");
    verifyNoMoreInteractions(carrier);
  }

  @TableTest({
    "scenario            | hexId                             ",
    "64-bit short        | '1'                               ",
    "64-bit max chars    | '123456789abcdef0'                ",
    "128-bit             | '123456789abcdef0123456789abcdef0'",
    "128-bit zero middle | '64184f2400000000123456789abcdef0'",
    "128-bit all f       | 'ffffffffffffffffffffffffffffffff'"
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeadersWith128BitTraceId(String hexId) {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DD128bTraceId traceId = DD128bTraceId.fromHex(hexId);
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext =
        new DDSpanContext(
            traceId,
            DDSpanId.from("2"),
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            UNSET,
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
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"));
    mockedContext.beginEndToEnd();
    String expectedT0 = String.valueOf(mockedContext.getEndToEndStartTime() / 1_000_000L);
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, traceId.toString());
    verify(carrier).put(SPAN_ID_KEY, "2");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "t0", expectedT0);
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k1", "v1");
    verify(carrier).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    if (traceId.toHighOrderLong() == 0) {
      verify(carrier).put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.anytag=value");
    } else {
      String tIdHex = LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16);
      verify(carrier)
          .put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.tid=" + tIdHex + ",_dd.p.anytag=value");
    }
    verifyNoMoreInteractions(carrier);
  }
}
