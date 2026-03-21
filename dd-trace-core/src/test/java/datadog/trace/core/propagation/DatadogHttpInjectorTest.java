package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.newInjector;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DatadogHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector =
      newInjector(Collections.singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));

  static Stream<Arguments> injectHttpHeadersArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of("1", "2", UNSET, null),
        Arguments.of("1", "2", SAMPLER_KEEP, "saipan"),
        Arguments.of(maxStr, maxMinus1Str, UNSET, "saipan"),
        Arguments.of(maxMinus1Str, maxStr, SAMPLER_KEEP, null));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersArguments")
  void injectHttpHeaders(String traceId, String spanId, int samplingPriority, String origin)
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
              PropagationTags.factory()
                  .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"));

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, traceId);
      verify(carrier, times(1)).put(SPAN_ID_KEY, spanId);
      if (samplingPriority != UNSET) {
        verify(carrier, times(1)).put(SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority));
      }
      if (origin != null) {
        verify(carrier, times(1)).put(ORIGIN_KEY, origin);
      }
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k2", "v2");
      verify(carrier, times(1)).put("SOME_CUSTOM_HEADER", "some-value");
      verify(carrier, times(1)).put(DATADOG_TAGS_KEY, "_dd.p.usr=123");
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  @Test
  void injectHttpHeadersWithEndToEnd() throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
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

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, "1");
      verify(carrier, times(1)).put(SPAN_ID_KEY, "2");
      verify(carrier, times(1)).put(ORIGIN_KEY, "fakeOrigin");
      verify(carrier, times(1))
          .put(
              OT_BAGGAGE_PREFIX + "t0",
              String.valueOf((long) (mockedContext.getEndToEndStartTime() / 1000000L)));
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k2", "v2");
      verify(carrier, times(1)).put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.anytag=value");
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  @Test
  void injectTheDecisionMakerTag() throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
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

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, "1");
      verify(carrier, times(1)).put(SPAN_ID_KEY, "2");
      verify(carrier, times(1)).put(ORIGIN_KEY, "fakeOrigin");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k2", "v2");
      verify(carrier, times(1)).put("x-datadog-sampling-priority", "2");
      verify(carrier, times(1)).put(DATADOG_TAGS_KEY, "_dd.p.dm=-4");
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> injectHttpHeadersWith128BitTraceIdArguments() {
    return Stream.of(
        Arguments.of("1"),
        Arguments.of("123456789abcdef0"),
        Arguments.of("123456789abcdef0123456789abcdef0"),
        Arguments.of("64184f2400000000123456789abcdef0"),
        Arguments.of(repeat("f", 32)));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersWith128BitTraceIdArguments")
  void injectHttpHeadersWith128BitTraceId(String hexId) throws Exception {
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DD128bTraceId traceId = DD128bTraceId.fromHex(hexId);
      Map<String, String> baggage = new HashMap<>();
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

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, traceId.toString());
      verify(carrier, times(1)).put(SPAN_ID_KEY, "2");
      verify(carrier, times(1))
          .put(
              OT_BAGGAGE_PREFIX + "t0",
              String.valueOf((long) (mockedContext.getEndToEndStartTime() / 1000000L)));
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier, times(1)).put(OT_BAGGAGE_PREFIX + "k2", "v2");
      if (traceId.toHighOrderLong() == 0) {
        verify(carrier, times(1)).put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.anytag=value");
      } else {
        String tId = LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16);
        verify(carrier, times(1))
            .put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.tid=" + tId + ",_dd.p.anytag=value");
      }
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
