package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.test.util.JavaStringUtils;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

public class HttpInjectorTest extends DDCoreJavaSpecification {

  protected boolean tracePropagationB3Padding() {
    return false;
  }

  private String idOrPadded(DDTraceId id) {
    if (id.toHighOrderLong() == 0) {
      return idOrPadded(DDSpanId.toHexString(id.toLong()), 32);
    }
    return id.toHexString();
  }

  private String idOrPadded(long id) {
    return idOrPadded(DDSpanId.toHexString(id), 16);
  }

  private String idOrPadded(String id, int size) {
    if (!tracePropagationB3Padding()) {
      return id.toLowerCase();
    }
    return JavaStringUtils.padHexLower(id, size);
  }

  @TableTest({
    "scenario                       | styles                       | samplingPriority              | origin  ",
    "DATADOG,B3SINGLE unset         | [DATADOG, B3SINGLE]          | PrioritySampling.UNSET        |         ",
    "DATADOG,B3SINGLE keep saipan   | [DATADOG, B3SINGLE]          | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "DATADOG only unset             | [DATADOG]                    | PrioritySampling.UNSET        |         ",
    "DATADOG only keep saipan       | [DATADOG]                    | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "B3SINGLE only unset            | [B3SINGLE]                   | PrioritySampling.UNSET        |         ",
    "B3SINGLE only keep saipan      | [B3SINGLE]                   | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "B3SINGLE,DATADOG keep saipan   | [B3SINGLE, DATADOG]          | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "DATADOG,B3MULTI,B3SINGLE unset | [DATADOG, B3MULTI, B3SINGLE] | PrioritySampling.UNSET        |         ",
    "DATADOG,B3MULTI,B3SINGLE keep  | [DATADOG, B3MULTI, B3SINGLE] | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "DATADOG,B3MULTI unset          | [DATADOG, B3MULTI]           | PrioritySampling.UNSET        |         ",
    "DATADOG,B3MULTI keep saipan    | [DATADOG, B3MULTI]           | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "B3MULTI only unset             | [B3MULTI]                    | PrioritySampling.UNSET        |         ",
    "B3MULTI only keep saipan       | [B3MULTI]                    | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "B3MULTI,DATADOG keep saipan    | [B3MULTI, DATADOG]           | PrioritySampling.SAMPLER_KEEP | 'saipan'"
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeadersUsingStyles(
      List<TracePropagationStyle> styles,
      @ConvertWith(PrioritySamplingConverter.class) int samplingPriority,
      String origin) {
    Set<TracePropagationStyle> styleSet = new LinkedHashSet<>(styles);
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToInject()).thenReturn(styleSet);
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    HttpCodec.Injector injector =
        HttpCodec.createInjector(
            config, config.getTracePropagationStylesToInject(), new LinkedHashMap<>());
    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2;
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext =
        mockedContext(tracer, traceId, spanId, samplingPriority, origin, baggage);
    Map<String, String> carrier = mock(Map.class);
    String b3TraceIdHex = idOrPadded(traceId);
    String b3SpanIdHex = idOrPadded(spanId);

    injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

    if (styleSet.contains(TracePropagationStyle.DATADOG)) {
      verify(carrier).put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString());
      verify(carrier).put(DatadogHttpCodec.SPAN_ID_KEY, Long.toString(spanId));
      verify(carrier).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2");
      if (samplingPriority != UNSET) {
        verify(carrier)
            .put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, Integer.toString(samplingPriority));
      }
      if (origin != null) {
        verify(carrier).put(DatadogHttpCodec.ORIGIN_KEY, origin);
      }
      verify(carrier).put("x-datadog-tags", "_dd.p.usr=123");
    }
    if (styleSet.contains(TracePropagationStyle.B3MULTI)) {
      verify(carrier).put(B3HttpCodec.TRACE_ID_KEY, b3TraceIdHex);
      verify(carrier).put(B3HttpCodec.SPAN_ID_KEY, b3SpanIdHex);
      if (samplingPriority != UNSET) {
        verify(carrier).put(SAMPLING_PRIORITY_KEY, "1");
      }
    }
    if (styleSet.contains(TracePropagationStyle.B3SINGLE)) {
      if (samplingPriority != UNSET) {
        verify(carrier).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex + "-1");
      } else {
        verify(carrier).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex);
      }
    }
    verifyNoMoreInteractions(carrier);
  }

  @TableTest({
    "scenario               | style    | samplingPriority              | origin  ",
    "DATADOG unset          | DATADOG  | PrioritySampling.UNSET        |         ",
    "DATADOG keep no origin | DATADOG  | PrioritySampling.SAMPLER_KEEP |         ",
    "DATADOG keep saipan    | DATADOG  | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "B3SINGLE unset         | B3SINGLE | PrioritySampling.UNSET        |         ",
    "B3SINGLE keep no orig  | B3SINGLE | PrioritySampling.SAMPLER_KEEP |         ",
    "B3SINGLE keep saipan   | B3SINGLE | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "B3MULTI unset          | B3MULTI  | PrioritySampling.UNSET        |         ",
    "B3MULTI keep no origin | B3MULTI  | PrioritySampling.SAMPLER_KEEP |         ",
    "B3MULTI keep saipan    | B3MULTI  | PrioritySampling.SAMPLER_KEEP | 'saipan'"
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeadersUsingStyle(
      TracePropagationStyle style,
      @ConvertWith(PrioritySamplingConverter.class) int samplingPriority,
      String origin) {
    Config config = mock(Config.class);
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    Map<String, String> mapping = new LinkedHashMap<>();
    mapping.put("some-baggage-item", "SOME_HEADER");
    HttpCodec.Injector injector = HttpCodec.createInjector(config, EnumSet.of(style), mapping);
    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2;
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put("some-baggage-item", "some-baggage-value");
    DDSpanContext mockedContext =
        mockedContext(tracer, traceId, spanId, samplingPriority, origin, baggage);
    Map<String, String> carrier = mock(Map.class);
    String b3TraceIdHex = idOrPadded(traceId);
    String b3SpanIdHex = idOrPadded(spanId);

    injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

    if (style == TracePropagationStyle.DATADOG) {
      verify(carrier).put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString());
      verify(carrier).put(DatadogHttpCodec.SPAN_ID_KEY, Long.toString(spanId));
      verify(carrier).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1");
      verify(carrier).put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2");
      verify(carrier).put("SOME_HEADER", "some-baggage-value");
      if (samplingPriority != UNSET) {
        verify(carrier)
            .put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, Integer.toString(samplingPriority));
      }
      if (origin != null) {
        verify(carrier).put(DatadogHttpCodec.ORIGIN_KEY, origin);
      }
      verify(carrier).put("x-datadog-tags", "_dd.p.usr=123");
    } else if (style == TracePropagationStyle.B3MULTI) {
      verify(carrier).put(B3HttpCodec.TRACE_ID_KEY, b3TraceIdHex);
      verify(carrier).put(B3HttpCodec.SPAN_ID_KEY, b3SpanIdHex);
      if (samplingPriority != UNSET) {
        verify(carrier).put(SAMPLING_PRIORITY_KEY, "1");
      }
    } else if (style == TracePropagationStyle.B3SINGLE) {
      if (samplingPriority != UNSET) {
        verify(carrier).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex + "-1");
      } else {
        verify(carrier).put(B3_KEY, b3TraceIdHex + "-" + b3SpanIdHex);
      }
    }
    verifyNoMoreInteractions(carrier);
  }

  @TableTest({
    "scenario              | style       ",
    "datadog encoding      | DATADOG     ",
    "tracecontext encoding | TRACECONTEXT",
    "haystack encoding     | HAYSTACK    "
  })
  @SuppressWarnings("unchecked")
  void encodeBaggageInHttpHeadersUsingStyle(TracePropagationStyle style) {
    Config config = mock(Config.class);
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    Map<String, String> baggage = new LinkedHashMap<>();
    baggage.put("alpha", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
    baggage.put("num", "01234567890");
    baggage.put("whitespace", "ab \tcd");
    baggage.put("specials", "ab.-*_cd");
    baggage.put("excluded", "ab',:\\cd");
    Map<String, String> mapping =
        baggage.keySet().stream().collect(Collectors.toMap(key -> key, key -> key));
    HttpCodec.Injector injector = HttpCodec.createInjector(config, EnumSet.of(style), mapping);
    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2;
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, UNSET, null, baggage);
    Map<String, String> carrier = mock(Map.class);

    injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

    verify(carrier).put("alpha", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
    verify(carrier).put("num", "01234567890");
    verify(carrier).put("whitespace", "ab%20%09cd");
    verify(carrier).put("specials", "ab.-*_cd");
    verify(carrier).put("excluded", "ab%27%2C%3A%5Ccd");
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
