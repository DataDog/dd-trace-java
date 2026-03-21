package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.B3MULTI;
import static datadog.trace.api.TracePropagationStyle.B3SINGLE;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.TracePropagationStyle.HAYSTACK;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import datadog.trace.test.util.StringUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpInjectorTest extends DDCoreSpecification {

  boolean tracePropagationB3Padding() {
    return false;
  }

  String idOrPadded(DDTraceId id) {
    if (id.toHighOrderLong() == 0) {
      return idOrPadded(DDSpanId.toHexString(id.toLong()), 32);
    }
    return id.toHexString();
  }

  String idOrPadded(long id) {
    return idOrPadded(DDSpanId.toHexString(id), 16);
  }

  String idOrPadded(String id, int size) {
    if (!tracePropagationB3Padding()) {
      return id.toLowerCase();
    }
    return StringUtils.padHexLower(id, size);
  }

  static Set<TracePropagationStyle> styles(TracePropagationStyle... vals) {
    return new LinkedHashSet<>(Arrays.asList(vals));
  }

  static Stream<Arguments> injectHttpHeadersArguments() {
    return Stream.of(
        Arguments.of(styles(DATADOG, B3SINGLE), UNSET, null),
        Arguments.of(styles(DATADOG, B3SINGLE), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(DATADOG), UNSET, null),
        Arguments.of(styles(DATADOG), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(B3SINGLE), UNSET, null),
        Arguments.of(styles(B3SINGLE), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(B3SINGLE, DATADOG), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(DATADOG, B3MULTI, B3SINGLE), UNSET, null),
        Arguments.of(styles(DATADOG, B3MULTI, B3SINGLE), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(DATADOG, B3MULTI), UNSET, null),
        Arguments.of(styles(DATADOG, B3MULTI), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(B3MULTI), UNSET, null),
        Arguments.of(styles(B3MULTI), SAMPLER_KEEP, "saipan"),
        Arguments.of(styles(B3MULTI, DATADOG), SAMPLER_KEEP, "saipan"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersArguments")
  void injectHttpHeadersUsingStyles(
      Set<TracePropagationStyle> stylesSet, int samplingPriority, String origin) throws Exception {
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToInject()).thenReturn(stylesSet);
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    HttpCodec.Injector injector =
        HttpCodec.createInjector(
            config,
            config.getTracePropagationStylesToInject(),
            Collections.<String, String>emptyMap());
    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2L;
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDSpanContext mockedContext =
          mockedContext(tracer, traceId, spanId, samplingPriority, origin, makeBasicBaggage());
      Map<String, String> carrier = mock(Map.class);
      String b3TraceIdHex = idOrPadded(traceId);
      String b3SpanIdHex = idOrPadded(spanId);

      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      if (stylesSet.contains(DATADOG)) {
        verify(carrier, times(1)).put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString());
        verify(carrier, times(1)).put(DatadogHttpCodec.SPAN_ID_KEY, String.valueOf(spanId));
        verify(carrier, times(1)).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1");
        verify(carrier, times(1)).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2");
        if (samplingPriority != UNSET) {
          verify(carrier, times(1))
              .put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority));
        }
        if (origin != null) {
          verify(carrier, times(1)).put(DatadogHttpCodec.ORIGIN_KEY, origin);
        }
        verify(carrier, times(1)).put("x-datadog-tags", "_dd.p.usr=123");
      }
      if (stylesSet.contains(B3MULTI)) {
        verify(carrier, times(1)).put(B3HttpCodec.TRACE_ID_KEY, b3TraceIdHex);
        verify(carrier, times(1)).put(B3HttpCodec.SPAN_ID_KEY, b3SpanIdHex);
        if (samplingPriority != UNSET) {
          verify(carrier, times(1)).put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1");
        }
      }
      if (stylesSet.contains(B3SINGLE)) {
        if (samplingPriority != UNSET) {
          verify(carrier, times(1)).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex + "-1");
        } else {
          verify(carrier, times(1)).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex);
        }
      }
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> injectHttpHeadersUsingStyleArguments() {
    return Stream.of(
        Arguments.of(DATADOG, UNSET, null),
        Arguments.of(DATADOG, SAMPLER_KEEP, null),
        Arguments.of(DATADOG, SAMPLER_KEEP, "saipan"),
        Arguments.of(B3SINGLE, UNSET, null),
        Arguments.of(B3SINGLE, SAMPLER_KEEP, null),
        Arguments.of(B3SINGLE, SAMPLER_KEEP, "saipan"),
        Arguments.of(B3MULTI, UNSET, null),
        Arguments.of(B3MULTI, SAMPLER_KEEP, null),
        Arguments.of(B3MULTI, SAMPLER_KEEP, "saipan"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersUsingStyleArguments")
  void injectHttpHeadersUsingStyle(TracePropagationStyle style, int samplingPriority, String origin)
      throws Exception {
    Config config = mock(Config.class);
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    Map<String, String> baggageMapping =
        Collections.singletonMap("some-baggage-item", "SOME_HEADER");
    HttpCodec.Injector injector =
        HttpCodec.createInjector(config, Collections.singleton(style), baggageMapping);
    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2L;
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k1", "v1");
      baggage.put("k2", "v2");
      baggage.put("some-baggage-item", "some-baggage-value");
      DDSpanContext mockedContext =
          mockedContext(tracer, traceId, spanId, samplingPriority, origin, baggage);
      Map<String, String> carrier = mock(Map.class);
      String b3TraceIdHex = idOrPadded(traceId);
      String b3SpanIdHex = idOrPadded(spanId);

      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      if (style == DATADOG) {
        verify(carrier, times(1)).put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString());
        verify(carrier, times(1)).put(DatadogHttpCodec.SPAN_ID_KEY, String.valueOf(spanId));
        verify(carrier, times(1)).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1");
        verify(carrier, times(1)).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2");
        verify(carrier, times(1)).put("SOME_HEADER", "some-baggage-value");
        if (samplingPriority != UNSET) {
          verify(carrier, times(1))
              .put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority));
        }
        if (origin != null) {
          verify(carrier, times(1)).put(DatadogHttpCodec.ORIGIN_KEY, origin);
        }
        verify(carrier, times(1)).put("x-datadog-tags", "_dd.p.usr=123");
      } else if (style == B3MULTI) {
        verify(carrier, times(1)).put(B3HttpCodec.TRACE_ID_KEY, b3TraceIdHex);
        verify(carrier, times(1)).put(B3HttpCodec.SPAN_ID_KEY, b3SpanIdHex);
        if (samplingPriority != UNSET) {
          verify(carrier, times(1)).put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1");
        }
      } else if (style == B3SINGLE) {
        if (samplingPriority != UNSET) {
          verify(carrier, times(1)).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex + "-1");
        } else {
          verify(carrier, times(1)).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex);
        }
      }
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> encodeBaggageInHttpHeadersArguments() {
    return Stream.of(Arguments.of(DATADOG), Arguments.of(TRACECONTEXT), Arguments.of(HAYSTACK));
  }

  @ParameterizedTest
  @MethodSource("encodeBaggageInHttpHeadersArguments")
  void encodeBaggageInHttpHeadersUsingStyle(TracePropagationStyle style) throws Exception {
    Config config = mock(Config.class);
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    Map<String, String> baggage = new HashMap<>();
    baggage.put("alpha", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
    baggage.put("num", "01234567890");
    baggage.put("whitespace", "ab \tcd");
    baggage.put("specials", "ab.-*_cd");
    baggage.put("excluded", "ab',:\\cd");
    Map<String, String> mapping = new HashMap<>();
    for (String key : baggage.keySet()) {
      mapping.put(key, key);
    }
    HttpCodec.Injector injector =
        HttpCodec.createInjector(config, Collections.singleton(style), mapping);
    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2L;
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, UNSET, null, baggage);
      Map<String, String> carrier = mock(Map.class);

      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1))
          .put("alpha", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
      verify(carrier, times(1)).put("num", "01234567890");
      verify(carrier, times(1)).put("whitespace", "ab%20%09cd");
      verify(carrier, times(1)).put("specials", "ab.-*_cd");
      verify(carrier, times(1)).put("excluded", "ab%27%2C%3A%5Ccd");
    } finally {
      tracer.close();
    }
  }

  static Map<String, String> makeBasicBaggage() {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    return baggage;
  }

  static DDSpanContext mockedContext(
      CoreTracer tracer,
      DDTraceId traceId,
      long spanId,
      int samplingPriority,
      String origin,
      Map<String, String> baggage) {
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
        PropagationTags.factory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"));
  }
}

class HttpInjectorB3PaddingTest extends HttpInjectorTest {
  @Override
  boolean tracePropagationB3Padding() {
    return true;
  }
}
