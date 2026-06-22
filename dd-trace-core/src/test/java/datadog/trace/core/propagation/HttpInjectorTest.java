package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_B3_PADDING_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3TestHelper.spanIdOrPadded;
import static datadog.trace.core.propagation.B3TestHelper.traceIdOrPadded;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.converter.PrioritySamplingConverter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class HttpInjectorTest extends AbstractHttpInjectorTest {

  protected boolean tracePropagationB3Padding() {
    return DEFAULT_PROPAGATION_B3_PADDING_ENABLED;
  }

  @TableTest({
    "scenario                       | styles                       | samplingPriority | origin  ",
    "DATADOG,B3SINGLE unset         | [DATADOG, B3SINGLE]          | UNSET            |         ",
    "DATADOG,B3SINGLE keep saipan   | [DATADOG, B3SINGLE]          | SAMPLER_KEEP     | 'saipan'",
    "DATADOG only unset             | [DATADOG]                    | UNSET            |         ",
    "DATADOG only keep saipan       | [DATADOG]                    | SAMPLER_KEEP     | 'saipan'",
    "B3SINGLE only unset            | [B3SINGLE]                   | UNSET            |         ",
    "B3SINGLE only keep saipan      | [B3SINGLE]                   | SAMPLER_KEEP     | 'saipan'",
    "B3SINGLE,DATADOG keep saipan   | [B3SINGLE, DATADOG]          | SAMPLER_KEEP     | 'saipan'",
    "DATADOG,B3MULTI,B3SINGLE unset | [DATADOG, B3MULTI, B3SINGLE] | UNSET            |         ",
    "DATADOG,B3MULTI,B3SINGLE keep  | [DATADOG, B3MULTI, B3SINGLE] | SAMPLER_KEEP     | 'saipan'",
    "DATADOG,B3MULTI unset          | [DATADOG, B3MULTI]           | UNSET            |         ",
    "DATADOG,B3MULTI keep saipan    | [DATADOG, B3MULTI]           | SAMPLER_KEEP     | 'saipan'",
    "B3MULTI only unset             | [B3MULTI]                    | UNSET            |         ",
    "B3MULTI only keep saipan       | [B3MULTI]                    | SAMPLER_KEEP     | 'saipan'",
    "B3MULTI,DATADOG keep saipan    | [B3MULTI, DATADOG]           | SAMPLER_KEEP     | 'saipan'"
  })
  void injectHttpHeadersUsingStyles(
      List<TracePropagationStyle> styles,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin) {
    HttpCodec.Injector injector = createInjector(styles, emptyMap());

    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2;
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    DDSpanContext mockedContext = mockedContext(traceId, spanId, samplingPriority, origin, baggage);

    Map<String, String> carrier = new HashMap<>();
    injector.inject(mockedContext, carrier, Map::put);

    String b3TraceIdHex = traceIdOrPadded(traceId, tracePropagationB3Padding());
    String b3SpanIdHex = spanIdOrPadded(spanId, tracePropagationB3Padding());
    int expectedSize = 0;
    if (styles.contains(TracePropagationStyle.DATADOG)) {
      assertEquals(traceId.toString(), carrier.get(DatadogHttpCodec.TRACE_ID_KEY));
      assertEquals(Long.toString(spanId), carrier.get(DatadogHttpCodec.SPAN_ID_KEY));
      assertEquals("v1", carrier.get(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1"));
      assertEquals("v2", carrier.get(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2"));
      assertEquals("_dd.p.usr=123", carrier.get(DATADOG_TAGS_KEY));
      expectedSize += 5;
      if (samplingPriority != UNSET) {
        assertEquals(
            Integer.toString(samplingPriority),
            carrier.get(DatadogHttpCodec.SAMPLING_PRIORITY_KEY));
        expectedSize++;
      }
      if (origin != null) {
        assertEquals(origin, carrier.get(DatadogHttpCodec.ORIGIN_KEY));
        expectedSize++;
      }
    }
    if (styles.contains(TracePropagationStyle.B3MULTI)) {
      assertEquals(b3TraceIdHex, carrier.get(B3HttpCodec.TRACE_ID_KEY));
      assertEquals(b3SpanIdHex, carrier.get(B3HttpCodec.SPAN_ID_KEY));
      expectedSize += 2;
      if (samplingPriority != UNSET) {
        assertEquals("1", carrier.get(SAMPLING_PRIORITY_KEY));
        expectedSize++;
      }
    }
    if (styles.contains(TracePropagationStyle.B3SINGLE)) {
      String expectedB3Value =
          samplingPriority != UNSET
              ? b3TraceIdHex + "-" + b3SpanIdHex + "-1"
              : b3TraceIdHex + "-" + b3SpanIdHex;
      assertEquals(expectedB3Value, carrier.get(B3_KEY));
      expectedSize++;
    }
    assertEquals(expectedSize, carrier.size());
  }

  @TableTest({
    "scenario               | style    | samplingPriority | origin  ",
    "DATADOG unset          | DATADOG  | UNSET            |         ",
    "DATADOG keep no origin | DATADOG  | SAMPLER_KEEP     |         ",
    "DATADOG keep saipan    | DATADOG  | SAMPLER_KEEP     | 'saipan'",
    "B3SINGLE unset         | B3SINGLE | UNSET            |         ",
    "B3SINGLE keep no orig  | B3SINGLE | SAMPLER_KEEP     |         ",
    "B3SINGLE keep saipan   | B3SINGLE | SAMPLER_KEEP     | 'saipan'",
    "B3MULTI unset          | B3MULTI  | UNSET            |         ",
    "B3MULTI keep no origin | B3MULTI  | SAMPLER_KEEP     |         ",
    "B3MULTI keep saipan    | B3MULTI  | SAMPLER_KEEP     | 'saipan'"
  })
  void injectHttpHeadersUsingStyle(
      TracePropagationStyle style,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin) {
    Map<String, String> mapping = new HashMap<>();
    mapping.put("some-baggage-item", "SOME_HEADER");
    HttpCodec.Injector injector = createInjector(singletonList(style), mapping);

    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2;
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    baggage.put("some-baggage-item", "some-baggage-value");
    DDSpanContext mockedContext = mockedContext(traceId, spanId, samplingPriority, origin, baggage);

    Map<String, String> carrier = new HashMap<>();
    injector.inject(mockedContext, carrier, Map::put);

    String b3TraceIdHex = traceIdOrPadded(traceId, tracePropagationB3Padding());
    String b3SpanIdHex = spanIdOrPadded(spanId, tracePropagationB3Padding());
    int expectedSize = 0;
    if (style == TracePropagationStyle.DATADOG) {
      assertEquals(traceId.toString(), carrier.get(DatadogHttpCodec.TRACE_ID_KEY));
      assertEquals(Long.toString(spanId), carrier.get(DatadogHttpCodec.SPAN_ID_KEY));
      assertEquals("v1", carrier.get(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1"));
      assertEquals("v2", carrier.get(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2"));
      assertEquals("some-baggage-value", carrier.get("SOME_HEADER"));
      assertEquals("_dd.p.usr=123", carrier.get(DATADOG_TAGS_KEY));
      expectedSize = 6;
      if (samplingPriority != UNSET) {
        assertEquals(
            Integer.toString(samplingPriority),
            carrier.get(DatadogHttpCodec.SAMPLING_PRIORITY_KEY));
        expectedSize++;
      }
      if (origin != null) {
        assertEquals(origin, carrier.get(DatadogHttpCodec.ORIGIN_KEY));
        expectedSize++;
      }
    } else if (style == TracePropagationStyle.B3MULTI) {
      assertEquals(b3TraceIdHex, carrier.get(B3HttpCodec.TRACE_ID_KEY));
      assertEquals(b3SpanIdHex, carrier.get(B3HttpCodec.SPAN_ID_KEY));
      expectedSize = 2;
      if (samplingPriority != UNSET) {
        assertEquals("1", carrier.get(SAMPLING_PRIORITY_KEY));
        expectedSize++;
      }
    } else if (style == TracePropagationStyle.B3SINGLE) {
      String expectedB3Value =
          samplingPriority != UNSET
              ? b3TraceIdHex + "-" + b3SpanIdHex + "-1"
              : b3TraceIdHex + "-" + b3SpanIdHex;
      assertEquals(expectedB3Value, carrier.get(B3_KEY));
      expectedSize++;
    }
    assertEquals(expectedSize, carrier.size());
  }

  @TableTest({
    "scenario              | style       ",
    "datadog encoding      | DATADOG     ",
    "tracecontext encoding | TRACECONTEXT",
    "haystack encoding     | HAYSTACK    "
  })
  void encodeBaggageInHttpHeadersUsingStyle(TracePropagationStyle style) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("alpha", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
    baggage.put("num", "01234567890");
    baggage.put("whitespace", "ab \tcd");
    baggage.put("specials", "ab.-*_cd");
    baggage.put("excluded", "ab',:\\cd");
    Map<String, String> mapping =
        baggage.keySet().stream().collect(Collectors.toMap(key -> key, key -> key));
    HttpCodec.Injector injector = createInjector(singletonList(style), mapping);

    DDTraceId traceId = DDTraceId.ONE;
    long spanId = 2;
    DDSpanContext mockedContext = mockedContext(traceId, spanId, UNSET, null, baggage);

    Map<String, String> carrier = new HashMap<>();
    injector.inject(mockedContext, carrier, Map::put);

    assertEquals("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", carrier.get("alpha"));
    assertEquals("01234567890", carrier.get("num"));
    assertEquals("ab%20%09cd", carrier.get("whitespace"));
    assertEquals("ab.-*_cd", carrier.get("specials"));
    assertEquals("ab%27%2C%3A%5Ccd", carrier.get("excluded"));
  }

  HttpCodec.Injector createInjector(
      List<TracePropagationStyle> overriddenStyles, Map<String, String> invertedBaggageMapping) {
    Config config = mock(Config.class);
    if (overriddenStyles != null) {
      LinkedHashSet<TracePropagationStyle> orderedSet = new LinkedHashSet<>(overriddenStyles);
      when(config.getTracePropagationStylesToInject()).thenReturn(orderedSet);
    }
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(tracePropagationB3Padding());
    return HttpCodec.createInjector(
        config, config.getTracePropagationStylesToInject(), invertedBaggageMapping);
  }

  DDSpanContext mockedContext(
      DDTraceId traceId,
      long spanId,
      int samplingPriority,
      String origin,
      Map<String, String> baggage) {
    return mockSpanContext(
        traceId,
        spanId,
        samplingPriority,
        origin,
        baggage,
        PropagationTags.factory().fromHeaderValue(DATADOG, "_dd.p.usr=123"));
  }

  static class HttpInjectorNonPaddedTest extends HttpInjectorTest {
    @Override
    protected boolean tracePropagationB3Padding() {
      return false;
    }
  }
}
