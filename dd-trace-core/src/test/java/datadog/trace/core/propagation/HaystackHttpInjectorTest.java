package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_PARENT_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_SPAN_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HaystackHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector;

  @BeforeEach
  void setup() {
    injector =
        HaystackHttpCodec.newInjector(
            Collections.singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));
  }

  static Stream<Arguments> injectHttpHeadersArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of(
            "1",
            "2",
            SAMPLER_KEEP,
            null,
            "44617461-646f-6721-0000-000000000001",
            "44617461-646f-6721-0000-000000000002"),
        Arguments.of(
            "1",
            "2",
            SAMPLER_KEEP,
            null,
            "44617461-646f-6721-0000-000000000001",
            "44617461-646f-6721-0000-000000000002"),
        Arguments.of(
            maxStr,
            maxMinus1Str,
            SAMPLER_KEEP,
            null,
            "44617461-646f-6721-ffff-ffffffffffff",
            "44617461-646f-6721-ffff-fffffffffffe"),
        Arguments.of(
            maxMinus1Str,
            maxStr,
            SAMPLER_KEEP,
            null,
            "44617461-646f-6721-ffff-fffffffffffe",
            "44617461-646f-6721-ffff-ffffffffffff"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersArguments")
  void injectHttpHeaders(
      String traceId,
      String spanId,
      int samplingPriority,
      String origin,
      String traceUuid,
      String spanUuid)
      throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
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
              null);

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, traceUuid);
      assertEquals(traceUuid, mockedContext.getTags().get(HAYSTACK_TRACE_ID_BAGGAGE_KEY));
      verify(carrier, times(1)).put(DD_TRACE_ID_BAGGAGE_KEY, traceId);
      verify(carrier, times(1)).put(SPAN_ID_KEY, spanUuid);
      verify(carrier, times(1)).put(DD_SPAN_ID_BAGGAGE_KEY, spanId);
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k2", "v2");
      verify(carrier, times(1)).put("SOME_CUSTOM_HEADER", "some-value");
      verify(carrier, times(1)).put(DD_PARENT_ID_BAGGAGE_KEY, "0");
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> injectHttpHeadersWithHaystackTraceIdInBaggageArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of(
            "1",
            "2",
            SAMPLER_KEEP,
            null,
            "54617461-646f-6721-0000-000000000001",
            "44617461-646f-6721-0000-000000000002"),
        Arguments.of(
            "1",
            "2",
            SAMPLER_KEEP,
            null,
            "54617461-646f-6721-0000-000000000001",
            "44617461-646f-6721-0000-000000000002"),
        Arguments.of(
            maxStr,
            maxMinus1Str,
            SAMPLER_KEEP,
            null,
            "54617461-646f-6721-ffff-ffffffffffff",
            "44617461-646f-6721-ffff-fffffffffffe"),
        Arguments.of(
            maxMinus1Str,
            maxStr,
            SAMPLER_KEEP,
            null,
            "54617461-646f-6721-ffff-fffffffffffe",
            "44617461-646f-6721-ffff-ffffffffffff"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersWithHaystackTraceIdInBaggageArguments")
  void injectHttpHeadersWithHaystackTraceIdInBaggage(
      String traceId,
      String spanId,
      int samplingPriority,
      String origin,
      String traceUuid,
      String spanUuid)
      throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      String haystackUuid = traceUuid;
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k1", "v1");
      baggage.put("k2", "v2");
      baggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, haystackUuid);
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
              null);

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, traceUuid);
      verify(carrier, times(1)).put(DD_TRACE_ID_BAGGAGE_KEY, traceId);
      verify(carrier, times(1)).put(SPAN_ID_KEY, spanUuid);
      verify(carrier, times(1)).put(DD_SPAN_ID_BAGGAGE_KEY, spanId);
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k2", "v2");
    } finally {
      tracer.close();
    }
  }
}
