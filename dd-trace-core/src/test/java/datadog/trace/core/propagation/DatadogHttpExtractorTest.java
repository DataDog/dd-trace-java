package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.internal.util.LongStringUtils;
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

class DatadogHttpExtractorTest extends DDCoreSpecification {

  DynamicConfig dynamicConfig;
  HttpCodec.Extractor _extractor;
  boolean origAppSecActive;

  HttpCodec.Extractor getExtractor() {
    if (_extractor == null) {
      _extractor = createExtractor(Config.get());
    }
    return _extractor;
  }

  HttpCodec.Extractor createExtractor(Config config) {
    return DatadogHttpCodec.newExtractor(config, () -> dynamicConfig.captureTraceConfig());
  }

  @BeforeEach
  void setup() {
    Map<String, String> headerTags = new HashMap<>();
    headerTags.put("SOME_HEADER", "some-tag");
    Map<String, String> baggageMapping = new HashMap<>();
    baggageMapping.put("SOME_CUSTOM_BAGGAGE_HEADER", "some-baggage");
    baggageMapping.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "some-CaseSensitive-baggage");
    dynamicConfig =
        DynamicConfig.create().setHeaderTags(headerTags).setBaggageMapping(baggageMapping).apply();
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true");
  }

  @AfterEach
  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive;
    getExtractor().cleanup();
  }

  static Stream<Arguments> extractHttpHeadersArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of("1", "2", PrioritySampling.UNSET, null, true),
        Arguments.of("2", "3", (int) PrioritySampling.SAMPLER_KEEP, "saipan", false),
        Arguments.of(maxStr, maxMinus1Str, (int) PrioritySampling.UNSET, "saipan", true),
        Arguments.of(maxMinus1Str, maxStr, (int) PrioritySampling.SAMPLER_KEEP, "saipan", false));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersArguments")
  void extractHttpHeaders(
      String traceId, String spanId, int samplingPriority, String origin, boolean allowComma)
      throws Exception {
    injectSysConfig(TracerConfig.REQUEST_HEADER_TAGS_COMMA_ALLOWED, String.valueOf(allowComma));
    HttpCodec.Extractor extractor = createExtractor(Config.get());
    try {
      Map<String, String> headers = new HashMap<>();
      headers.put("", "empty key");
      headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
      headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
      headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
      headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
      headers.put("SOME_HEADER", "my-interesting-info,and-more");
      headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "my-interesting-baggage-info");
      headers.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "my-interesting-baggage-info-2");
      String expectedTagValue = allowComma ? "my-interesting-info,and-more" : "my-interesting-info";

      if (samplingPriority != (int) PrioritySampling.UNSET) {
        headers.put(SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority));
      }
      if (origin != null) {
        headers.put(ORIGIN_KEY, origin);
      }

      ExtractedContext context =
          (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

      assertNotNull(context);
      assertEquals(DDTraceId.from(traceId), context.getTraceId());
      assertEquals(DDSpanId.from(spanId), context.getSpanId());
      Map<String, String> expectedBaggage = new HashMap<>();
      expectedBaggage.put("k1", "v1");
      expectedBaggage.put("k2", "v2");
      expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
      expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
      assertEquals(expectedBaggage, context.getBaggage());
      assertEquals(Collections.singletonMap("some-tag", expectedTagValue), context.getTags());
      assertEquals(samplingPriority, context.getSamplingPriority());
      assertEquals(origin, context.getOrigin() == null ? null : context.getOrigin().toString());
    } finally {
      extractor.cleanup();
    }
  }

  @Test
  void extractHeaderTagsWithNoPropagationWithoutOrigin() {
    Map<String, String> headers = Collections.singletonMap("SOME_HEADER", "my-interesting-info");
    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHeaderTagsWithNoPropagationWithOrigin() {
    Map<String, String> headers = new HashMap<>();
    headers.put(ORIGIN_KEY, "my-origin");
    headers.put("SOME_HEADER", "my-interesting-info");
    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(
        "my-origin",
        ((TagContext) context).getOrigin() == null
            ? null
            : ((TagContext) context).getOrigin().toString());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    String forwardedValue = "for=" + forwardedIp + ":" + forwardedPort;

    Map<String, String> tagOnlyCtx = Collections.singletonMap("Forwarded", forwardedValue);
    TagContext context = getExtractor().extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwardedValue, context.getForwarded());

    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("Forwarded", forwardedValue);
    context = getExtractor().extract(fullCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwardedValue, context.getForwarded());
  }

  @Test
  void extractHeadersWithXForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";

    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", forwardedIp);
    tagOnlyCtx.put("X-Forwarded-Port", forwardedPort);
    TagContext context = getExtractor().extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertInstanceOf(TagContext.class, context);
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());

    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("x-forwarded-for", forwardedIp);
    fullCtx.put("x-forwarded-port", forwardedPort);
    context = getExtractor().extract(fullCtx, ContextVisitors.stringValuesMap());
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
        getExtractor()
            .extract(
                Collections.singletonMap("ignored-header", "ignored-value"),
                ContextVisitors.stringValuesMap()));
  }

  @Test
  void extractHeadersWithIpResolutionDisabled() {
    injectSysConfig(TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED, "false");
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("User-agent", "foo/bar");

    TagContext ctx = getExtractor().extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(ctx);
    assertNull(ctx.getXForwardedFor());
    assertEquals("foo/bar", ctx.getUserAgent());
  }

  @Test
  void extractHeadersWithIpResolutionDisabledAppsecDisabledVariant() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("User-agent", "foo/bar");

    TagContext ctx = getExtractor().extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(ctx);
    assertNull(ctx.getXForwardedFor());
  }

  @Test
  void customIpHeaderCollectionDoesNotDisableStandardIpHeaderCollection() {
    injectSysConfig(TracerConfig.TRACE_CLIENT_IP_HEADER, "my-header");
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("My-Header", "8.8.8.8");

    TagContext ctx = getExtractor().extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(ctx);
    assertEquals("::1", ctx.getXForwardedFor());
    assertEquals("8.8.8.8", ctx.getCustomIpHeader());
  }

  static Stream<Arguments> extractHttpHeadersWith128BitTraceIdArguments() {
    return Stream.of(
        Arguments.of("1"),
        Arguments.of("123456789abcdef0"),
        Arguments.of("123456789abcdef0123456789abcdef0"),
        Arguments.of("64184f2400000000123456789abcdef0"),
        Arguments.of(repeat("f", 32)));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersWith128BitTraceIdArguments")
  void extractHttpHeadersWith128BitTraceId(String hexId) {
    DD128bTraceId traceId = DD128bTraceId.fromHex(hexId);
    boolean is128bTrace = traceId.toHighOrderLong() != 0;
    DDTraceId expectedTraceId = is128bTrace ? traceId : DD64bTraceId.from(traceId.toLong());

    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId.toString());
    headers.put(SPAN_ID_KEY.toUpperCase(), "2");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");
    if (is128bTrace) {
      headers.put(
          DATADOG_TAGS_KEY.toUpperCase(),
          "_dd.p.tid=" + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16));
    }

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(expectedTraceId, context.getTraceId());
    assertEquals(DDSpanId.from("2"), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "traceId");
    headers.put(SPAN_ID_KEY.toUpperCase(), "spanId");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
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

    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
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

    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
    assertNull(context);
  }

  static Stream<Arguments> moreIdRangeValidationArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    String maxPlus1Str = TRACE_ID_MAX.add(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of("-1", "1", null, null),
        Arguments.of("1", "-1", null, null),
        Arguments.of("0", "1", null, null),
        Arguments.of("1", "0", DD64bTraceId.ONE, DDSpanId.ZERO),
        Arguments.of(maxStr, "1", DD64bTraceId.MAX, 1L),
        Arguments.of(maxPlus1Str, "1", null, null),
        Arguments.of("1", maxStr, DD64bTraceId.ONE, DDSpanId.MAX),
        Arguments.of("1", maxPlus1Str, null, null));
  }

  @ParameterizedTest
  @MethodSource("moreIdRangeValidationArguments")
  void moreIdRangeValidation(
      String traceId, String spanId, Object expectedTraceId, Object expectedSpanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    if (expectedTraceId != null) {
      assertNotNull(context);
      assertEquals(expectedTraceId, context.getTraceId());
      long expectedSpanLong =
          expectedSpanId instanceof Long ? (Long) expectedSpanId : ((Long) expectedSpanId);
      assertEquals(expectedSpanLong, context.getSpanId());
    } else {
      assertNull(context);
    }
  }

  static Stream<Arguments> extractHttpHeadersWithEndToEndArguments() {
    return Stream.of(Arguments.of("1", "2", 0L), Arguments.of("2", "3", 1610001234L));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersWithEndToEndArguments")
  void extractHttpHeadersWithEndToEnd(String traceId, String spanId, long endToEndStartTime) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "t0", String.valueOf(endToEndStartTime));
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "my-interesting-baggage-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "my-interesting-baggage-info-2");

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(endToEndStartTime * 1000000L, context.getEndToEndStartTime());
  }

  static Stream<Arguments> baggageIsMappedOnContextCreationArguments() {
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of(false, "-1", "1"),
        Arguments.of(false, "1", "-1"),
        Arguments.of(true, "0", "1"),
        Arguments.of(true, maxMinus1Str, maxMinus1Str));
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

    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    if (ctxCreated) {
      assertNotNull(context);
      Map<String, String> expectedBaggage = new HashMap<>();
      expectedBaggage.put("some-baggage", "mappedBaggageValue");
      expectedBaggage.put("k1", "v1");
      expectedBaggage.put("k2", "v2");
      assertEquals(expectedBaggage, context.getBaggage());
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

    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
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
