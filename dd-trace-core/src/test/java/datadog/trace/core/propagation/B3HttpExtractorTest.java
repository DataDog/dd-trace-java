package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
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

class B3HttpExtractorTest extends DDCoreSpecification {

  DynamicConfig dynamicConfig;
  HttpCodec.Extractor extractor;
  boolean origAppSecActive;

  @BeforeEach
  void setup() {
    dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.singletonMap("SOME_HEADER", "some-tag"))
            .setBaggageMapping(Collections.<String, String>emptyMap())
            .apply();
    extractor = B3HttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true");
  }

  @AfterEach
  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive;
  }

  static Stream<Arguments> extractHttpHeadersArguments() {
    String maxHex = TRACE_ID_MAX.toString(16).toLowerCase();
    String maxMinus1Hex = TRACE_ID_MAX.subtract(BigInteger.ONE).toString(16).toLowerCase();
    return Stream.of(
        Arguments.of("1", "2", null, (int) PrioritySampling.UNSET),
        Arguments.of("2", "3", "1", (int) PrioritySampling.SAMPLER_KEEP),
        Arguments.of("3", "4", "0", (int) PrioritySampling.SAMPLER_DROP),
        Arguments.of(maxHex, maxMinus1Hex, "0", (int) PrioritySampling.SAMPLER_DROP),
        Arguments.of(maxMinus1Hex, maxHex, "1", (int) PrioritySampling.SAMPLER_KEEP));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersArguments")
  void extractHttpHeaders(
      String traceIdHex,
      String spanIdHex,
      String samplingPriorityStr,
      int expectedSamplingPriority) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceIdHex);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanIdHex);
    headers.put("SOME_HEADER", "my-interesting-info");
    if (samplingPriorityStr != null) {
      headers.put(SAMPLING_PRIORITY_KEY, samplingPriorityStr);
    }

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(B3TraceId.fromHex(traceIdHex), context.getTraceId());
    assertEquals(DDSpanId.fromHex(spanIdHex), context.getSpanId());
    assertTrue(context.getBaggage().isEmpty());
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("b3.traceid", ((B3TraceId) context.getTraceId()).getOriginal());
    expectedTags.put("b3.spanid", DDSpanId.toHexString(context.getSpanId()));
    expectedTags.put("some-tag", "my-interesting-info");
    assertEquals(expectedTags, context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  static Stream<Arguments> extractHttpHeadersWithB3AtBeginningArguments() {
    return Stream.of(
        // b3 | expectedTraceIdHex | expectedSpanIdHex | expectedSamplingPriority
        Arguments.of("2-3-0", "2", "3", (int) PrioritySampling.SAMPLER_DROP),
        Arguments.of("2-3", "2", "3", (int) PrioritySampling.UNSET),
        Arguments.of("0", "1", "2", (int) PrioritySampling.SAMPLER_KEEP), // B3 Multi used instead
        Arguments.of(null, "1", "2", (int) PrioritySampling.SAMPLER_KEEP) // B3 Multi used instead
        );
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersWithB3AtBeginningArguments")
  void extractHttpHeadersWithB3HeaderAtTheBeginning(
      String b3,
      String expectedTraceIdHex,
      String expectedSpanIdHex,
      int expectedSamplingPriority) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    if (b3 != null) {
      headers.put(B3_KEY, b3);
    }
    headers.put(TRACE_ID_KEY.toUpperCase(), "1");
    headers.put(SPAN_ID_KEY.toUpperCase(), "2");
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put(SAMPLING_PRIORITY_KEY, "1");

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(B3TraceId.fromHex(expectedTraceIdHex), context.getTraceId());
    assertEquals(DDSpanId.fromHex(expectedSpanIdHex), context.getSpanId());
    assertTrue(context.getBaggage().isEmpty());
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("b3.traceid", ((B3TraceId) context.getTraceId()).getOriginal());
    expectedTags.put("b3.spanid", DDSpanId.toHexString(context.getSpanId()));
    expectedTags.put("some-tag", "my-interesting-info");
    assertEquals(expectedTags, context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  static Stream<Arguments> extractHttpHeadersWithB3AtEndArguments() {
    return Stream.of(
        Arguments.of("2-3-0", "2", "3", (int) PrioritySampling.SAMPLER_DROP),
        Arguments.of("2-3", "2", "3", (int) PrioritySampling.UNSET),
        Arguments.of("0", "1", "2", (int) PrioritySampling.SAMPLER_KEEP),
        Arguments.of(null, "1", "2", (int) PrioritySampling.SAMPLER_KEEP));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersWithB3AtEndArguments")
  void extractHttpHeadersWithB3HeaderAtTheEnd(
      String b3,
      String expectedTraceIdHex,
      String expectedSpanIdHex,
      int expectedSamplingPriority) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), "1");
    headers.put(SPAN_ID_KEY.toUpperCase(), "2");
    if (b3 != null) {
      headers.put(B3_KEY, b3);
    }
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put(SAMPLING_PRIORITY_KEY, "1");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(B3TraceId.fromHex(expectedTraceIdHex), context.getTraceId());
    assertEquals(DDSpanId.fromHex(expectedSpanIdHex), context.getSpanId());
    assertTrue(context.getBaggage().isEmpty());
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("b3.traceid", ((B3TraceId) context.getTraceId()).getOriginal());
    expectedTags.put("b3.spanid", DDSpanId.toHexString(context.getSpanId()));
    expectedTags.put("some-tag", "my-interesting-info");
    assertEquals(expectedTags, context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  static Stream<Arguments> extract128BitIdArguments() {
    return Stream.of(
        Arguments.of("-1", "1", null, null),
        Arguments.of("1", "-1", null, null),
        Arguments.of("0", "1", null, null),
        Arguments.of("00001", "1", B3TraceId.fromHex("00001"), DDSpanId.fromHex("00001")),
        Arguments.of(
            "463ac35c9f6413ad",
            "463ac35c9f6413ad",
            B3TraceId.fromHex("463ac35c9f6413ad"),
            DDSpanId.from("5060571933882717101")),
        Arguments.of(
            "463ac35c9f6413ad48485a3953bb6124",
            "1",
            B3TraceId.fromHex("463ac35c9f6413ad48485a3953bb6124"),
            1L),
        Arguments.of(repeat("f", 16), "1", B3TraceId.fromHex(repeat("f", 16)), 1L),
        Arguments.of(
            repeat("a", 16) + repeat("f", 16),
            "1",
            B3TraceId.fromHex(repeat("a", 16) + repeat("f", 16)),
            1L),
        Arguments.of("1" + repeat("f", 32), "1", null, null),
        Arguments.of("0" + repeat("f", 32), "1", null, null),
        Arguments.of("1", repeat("f", 16), B3TraceId.fromHex("1"), DDSpanId.MAX),
        Arguments.of("1", "1" + repeat("f", 16), null, null));
  }

  @ParameterizedTest
  @MethodSource("extract128BitIdArguments")
  void extract128BitIdTruncatesIdTo64Bit(
      String traceId, String spanId, Object expectedTraceId, Object expectedSpanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    if (expectedTraceId != null) {
      assertNotNull(context);
      assertInstanceOf(ExtractedContext.class, context);
      ExtractedContext ec = (ExtractedContext) context;
      assertEquals(expectedTraceId, ec.getTraceId());
      long expectedSpanLong = expectedSpanId instanceof Long ? (Long) expectedSpanId : 0L;
      assertEquals(expectedSpanLong, ec.getSpanId());
      assertEquals(((B3TraceId) ec.getTraceId()).getOriginal(), ec.getTags().get("b3.traceid"));
      assertEquals(DDSpanId.toHexString(ec.getSpanId()), ec.getTags().get("b3.spanid"));
    } else {
      assertTrue(
          context == null
              || (context instanceof TagContext && !(context instanceof ExtractedContext)));
    }
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
    String forwardedPort = "1234";
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
    String forwardedPort = "1234";

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
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHttpHeadersWithOutOfRangeSpanId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "0");
    headers.put(SPAN_ID_KEY.toUpperCase(), "-1");
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  static Stream<Arguments> extractIdsRetainingOriginalArguments() {
    return Stream.of(
        Arguments.of("00001", "00001", B3TraceId.fromHex("00001"), 1L),
        Arguments.of(
            "463ac35c9f6413ad",
            "463ac35c9f6413ad",
            B3TraceId.fromHex("463ac35c9f6413ad"),
            DDSpanId.from("5060571933882717101")),
        Arguments.of(
            "463ac35c9f6413ad48485a3953bb6124",
            "1",
            B3TraceId.fromHex("463ac35c9f6413ad48485a3953bb6124"),
            1L),
        Arguments.of(repeat("f", 16), "1", B3TraceId.fromHex(repeat("f", 16)), 1L),
        Arguments.of(
            repeat("a", 16) + repeat("f", 16),
            "1",
            B3TraceId.fromHex(repeat("a", 16) + repeat("f", 16)),
            1L),
        Arguments.of("1", repeat("f", 16), B3TraceId.fromHex("1"), DDSpanId.MAX),
        Arguments.of("1", "000" + repeat("f", 16), B3TraceId.fromHex("1"), DDSpanId.MAX));
  }

  @ParameterizedTest
  @MethodSource("extractIdsRetainingOriginalArguments")
  void extractIdsWhileRetainingTheOriginalString(
      String traceId, String spanId, Object expectedTraceId, long expectedSpanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

    if (expectedTraceId != null) {
      assertNotNull(context);
      assertEquals(expectedTraceId, context.getTraceId());
      assertEquals(traceId, ((B3TraceId) context.getTraceId()).getOriginal());
      assertEquals(expectedSpanId, context.getSpanId());
      assertEquals(trimmed(spanId), DDSpanId.toHexString(context.getSpanId()));
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

  static String trimmed(String hex) {
    int length = hex.length();
    int i = 0;
    while (i < length && hex.charAt(i) == '0') {
      i++;
    }
    if (i == length) {
      return "0";
    }
    return hex.substring(i, length);
  }

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
