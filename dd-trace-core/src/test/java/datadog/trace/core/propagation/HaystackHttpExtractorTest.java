package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HaystackHttpExtractorTest extends DDCoreSpecification {

  DynamicConfig dynamicConfig;
  HttpCodec.Extractor extractor;
  boolean origAppSecActive;

  @BeforeEach
  void setup() {
    Map<String, String> headerTags = new HashMap<>();
    headerTags.put("SOME_HEADER", "some-tag");
    Map<String, String> baggageMapping = new HashMap<>();
    baggageMapping.put("SOME_CUSTOM_BAGGAGE_HEADER", "some-baggage");
    baggageMapping.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "some-CaseSensitive-baggage");
    dynamicConfig =
        DynamicConfig.create().setHeaderTags(headerTags).setBaggageMapping(baggageMapping).apply();
    extractor =
        HaystackHttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true");
  }

  @AfterEach
  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive;
  }

  static Stream<Arguments> extractHttpHeadersArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    String maxMinus6Str = TRACE_ID_MAX.subtract(BigInteger.valueOf(6)).toString();
    String maxMinus7Str = TRACE_ID_MAX.subtract(BigInteger.valueOf(7)).toString();
    return Stream.of(
        Arguments.of(
            "1",
            "2",
            PrioritySampling.SAMPLER_KEEP,
            null,
            "44617461-646f-6721-0000-000000000001",
            "44617461-646f-6721-0000-000000000002"),
        Arguments.of(
            "2",
            "3",
            PrioritySampling.SAMPLER_KEEP,
            null,
            "44617461-646f-6721-0000-000000000002",
            "44617461-646f-6721-0000-000000000003"),
        Arguments.of(
            maxStr,
            maxMinus6Str,
            PrioritySampling.SAMPLER_KEEP,
            null,
            "44617461-646f-6721-ffff-ffffffffffff",
            "44617461-646f-6721-ffff-fffffffffff9"),
        Arguments.of(
            maxMinus1Str,
            maxMinus7Str,
            PrioritySampling.SAMPLER_KEEP,
            null,
            "44617461-646f-6721-ffff-fffffffffffe",
            "44617461-646f-6721-ffff-fffffffffff8"));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersArguments")
  void extractHttpHeaders(
      String traceId,
      String spanId,
      int samplingPriority,
      String origin,
      String traceUuid,
      String spanUuid) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceUuid);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanUuid);
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "%76%32"); // v2 encoded once
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k3", "%25%37%36%25%33%33"); // v3 encoded twice
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "my-interesting-baggage-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "my-interesting-baggage-info-2");

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put("k3", "%76%33"); // expect value decoded only once
    expectedBaggage.put("Haystack-Trace-ID", traceUuid);
    expectedBaggage.put("Haystack-Span-ID", spanUuid);
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(samplingPriority, context.getSamplingPriority());
    assertEquals(origin, context.getOrigin() == null ? null : context.getOrigin().toString());
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    Map<String, String> headers = Collections.singletonMap("SOME_HEADER", "my-interesting-info");
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "123";
    String forwardedValue = "for=" + forwardedIp + ":" + forwardedPort;

    Map<String, String> tagOnlyCtx = Collections.singletonMap("Forwarded", forwardedValue);
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwardedValue, context.getForwarded());

    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("Forwarded", forwardedValue);
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwardedValue, context.getForwarded());
  }

  @Test
  void extractHeadersWithXForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "123";

    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", forwardedIp);
    tagOnlyCtx.put("X-Forwarded-Port", forwardedPort);
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertInstanceOf(TagContext.class, context);
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());

    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("x-forwarded-for", forwardedIp);
    fullCtx.put("x-forwarded-port", forwardedPort);
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());
  }

  @Test
  void extractEmptyHeadersReturnsNull() {
    assertNull(
        extractor.extract(
            Collections.singletonMap("ignored-header", "ignored-value"),
            ContextVisitors.stringValuesMap()));
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "traceId");
    headers.put(SPAN_ID_KEY.toUpperCase(), "spanId");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithOutOfRangeTraceId() {
    String outOfRangeTraceId = TRACE_ID_MAX.add(BigInteger.ONE).toString();
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), outOfRangeTraceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), "0");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithOutOfRangeSpanId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "0");
    headers.put(SPAN_ID_KEY.toUpperCase(), "-1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNull(context);
  }

  static Stream<Arguments> baggageIsMappedOnContextCreationArguments() {
    return Stream.of(
        Arguments.of(false, "-1", "1"),
        Arguments.of(false, "1", "-1"),
        Arguments.of(true, "0", "1"),
        Arguments.of(
            true, "44617461-646f-6721-463a-c35c9f6413ad", "44617461-646f-6721-463a-c35c9f6413ad"));
  }

  @ParameterizedTest
  @MethodSource("baggageIsMappedOnContextCreationArguments")
  void baggageIsMappedOnContextCreation(boolean ctxCreated, String traceId, String spanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "mappedBaggageValue");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_ARBITRARY_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    if (ctxCreated) {
      assertNotNull(context);
      Map<String, String> expectedBaggage = new HashMap<>();
      expectedBaggage.put("Haystack-Trace-ID", traceId);
      expectedBaggage.put("Haystack-Span-ID", spanId);
      expectedBaggage.put("some-baggage", "mappedBaggageValue");
      expectedBaggage.put("k1", "v1");
      expectedBaggage.put("k2", "v2");
      assertEquals(expectedBaggage, context.getBaggage());
    } else {
      assertNull(context);
    }
  }

  static Stream<Arguments> extract128BitIdArguments() {
    return Stream.of(
        Arguments.of(false, "-1", "1", null, DDSpanId.ZERO),
        Arguments.of(false, "1", "-1", null, DDSpanId.ZERO),
        Arguments.of(true, "0", "1", null, DDSpanId.ZERO),
        Arguments.of(true, "00001", "00001", DDTraceId.ONE, 1L),
        Arguments.of(
            true,
            "463ac35c9f6413ad",
            "463ac35c9f6413ad",
            DDTraceId.from(5060571933882717101L),
            5060571933882717101L),
        Arguments.of(
            true,
            "463ac35c9f6413ad48485a3953bb6124",
            "1",
            DDTraceId.from(5208512171318403364L),
            1L),
        Arguments.of(
            true,
            "44617461-646f-6721-463a-c35c9f6413ad",
            "44617461-646f-6721-463a-c35c9f6413ad",
            DDTraceId.from(5060571933882717101L),
            5060571933882717101L),
        Arguments.of(true, repeat("f", 16), "1", DD64bTraceId.MAX, 1L),
        Arguments.of(true, repeat("a", 16) + repeat("f", 16), "1", DD64bTraceId.MAX, 1L),
        Arguments.of(false, "1" + repeat("f", 32), "1", null, 1L),
        Arguments.of(false, "0" + repeat("f", 32), "1", null, 1L),
        Arguments.of(true, "1", repeat("f", 16), DDTraceId.ONE, DDSpanId.MAX),
        Arguments.of(false, "1", "1" + repeat("f", 16), null, DDSpanId.ZERO),
        Arguments.of(true, "1", "000" + repeat("f", 16), DDTraceId.ONE, DDSpanId.MAX));
  }

  @ParameterizedTest
  @MethodSource("extract128BitIdArguments")
  void extract128BitIdTruncatesIdTo64Bit(
      boolean ctxCreated,
      String traceId,
      String spanId,
      Object expectedTraceId,
      long expectedSpanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    if (expectedTraceId != null) {
      assertNotNull(context);
      assertEquals(expectedTraceId, context.getTraceId());
      assertEquals(expectedSpanId, context.getSpanId());
    }
    if (ctxCreated) {
      assertNotNull(context);
    } else {
      assertNull(context);
    }
  }

  @Test
  void extractCommonHttpHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put(HttpCodec.USER_AGENT_KEY, "some-user-agent");
    headers.put(HttpCodec.X_CLUSTER_CLIENT_IP_KEY, "1.1.1.1");
    headers.put(HttpCodec.X_REAL_IP_KEY, "2.2.2.2");
    headers.put(HttpCodec.X_CLIENT_IP_KEY, "3.3.3.3");
    headers.put(HttpCodec.TRUE_CLIENT_IP_KEY, "4.4.4.4");
    headers.put(HttpCodec.FORWARDED_FOR_KEY, "5.5.5.5");
    headers.put(HttpCodec.FORWARDED_KEY, "6.6.6.6");
    headers.put(HttpCodec.FASTLY_CLIENT_IP_KEY, "7.7.7.7");
    headers.put(HttpCodec.CF_CONNECTING_IP_KEY, "8.8.8.8");
    headers.put(HttpCodec.CF_CONNECTING_IP_V6_KEY, "9.9.9.9");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertEquals("some-user-agent", context.getUserAgent());
    assertEquals("1.1.1.1", context.getXClusterClientIp());
    assertEquals("2.2.2.2", context.getXRealIp());
    assertEquals("3.3.3.3", context.getXClientIp());
    assertEquals("4.4.4.4", context.getTrueClientIp());
    assertEquals("5.5.5.5", context.getForwardedFor());
    assertEquals("6.6.6.6", context.getForwarded());
    assertEquals("7.7.7.7", context.getFastlyClientIp());
    assertEquals("8.8.8.8", context.getCfConnectingIp());
    assertEquals("9.9.9.9", context.getCfConnectingIpv6());
  }

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
