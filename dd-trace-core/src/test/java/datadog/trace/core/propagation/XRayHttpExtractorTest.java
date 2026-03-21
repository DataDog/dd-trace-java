package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
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

class XRayHttpExtractorTest extends DDCoreSpecification {

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
    extractor = XRayHttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true");
  }

  @AfterEach
  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive;
  }

  static Stream<Arguments> extractHttpHeadersArguments() {
    return Stream.of(
        Arguments.of("1", "2", "", (int) PrioritySampling.UNSET),
        Arguments.of("2", "3", ";Sampled=1", (int) PrioritySampling.SAMPLER_KEEP),
        Arguments.of("3", "4", ";Sampled=0", (int) PrioritySampling.SAMPLER_DROP),
        Arguments.of(
            repeat("f", 16),
            repeat("f", 15) + "e",
            ";Sampled=0",
            (int) PrioritySampling.SAMPLER_DROP),
        Arguments.of(
            repeat("f", 15) + "e",
            repeat("f", 16),
            ";Sampled=1",
            (int) PrioritySampling.SAMPLER_KEEP));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersArguments")
  void extractHttpHeaders(
      String traceId, String spanId, String samplingPriority, int expectedSamplingPriority)
      throws Exception {
    String paddedTraceId = padLeft(traceId, 16, '0');
    String paddedSpanId = padLeft(spanId, 16, '0');
    Map<String, String> headers = new HashMap<>();
    headers.put(
        "X-Amzn-Trace-Id",
        "Root=1-00000000-00000000"
            + paddedTraceId
            + ";"
            + "Parent="
            + paddedSpanId
            + samplingPriority
            + ";=empty key;empty value=;=;;");
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "my-interesting-baggage-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "my-interesting-baggage-info-2");

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(DDTraceId.fromHex(traceId), context.getTraceId());
    assertEquals(DDSpanId.fromHex(spanId), context.getSpanId());

    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("empty value", "");
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());

    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
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
    fullCtx.put(
        "x-amzn-trace-id", "Root=1-00000000-000000000000000000000001;Parent=0000000000000002");
    fullCtx.put("Forwarded", forwardedValue);
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertTrue(context instanceof ExtractedContext);
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
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());

    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(
        "x-amzn-trace-id", "Root=1-00000000-000000000000000000000001;Parent=0000000000000002");
    fullCtx.put("x-forwarded-for", forwardedIp);
    fullCtx.put("x-forwarded-port", forwardedPort);
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertTrue(context instanceof ExtractedContext);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());
  }

  @Test
  void noContextWithEmptyHeaders() {
    assertNull(
        extractor.extract(
            Collections.singletonMap("ignored-header", "ignored-value"),
            ContextVisitors.stringValuesMap()));
  }

  @Test
  void noContextWithInvalidNonNumericId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(
        "x-amzn-trace-Id", "Root=1-00000000-00000000000000000traceId;Parent=0000000000spanId");
    headers.put("SOME_HEADER", "my-interesting-info");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNull(context);
  }

  @Test
  void noContextWithTooLargeTraceId() {
    Map<String, String> headers =
        Collections.singletonMap(
            "X-Amzn-Trace-Id", "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithNonZeroEpoch() {
    Map<String, String> headers =
        Collections.singletonMap(
            "X-Amzn-Trace-Id", "Root=1-5759e988-00000000e1be46a994272793;Parent=53995c3f42cd8ad8");

    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(DDTraceId.fromHex("e1be46a994272793"), context.getTraceId());
    assertEquals(DDSpanId.fromHex("53995c3f42cd8ad8"), context.getSpanId());
    assertNull(context.getOrigin());
  }

  static Stream<Arguments> extractIdsWhileRetainingOriginalStringArguments() {
    return Stream.of(
        Arguments.of("00001", "00001", DD64bTraceId.ONE, 1L),
        Arguments.of(
            "463ac35c9f6413ad",
            "463ac35c9f6413ad",
            DD64bTraceId.fromHex("463ac35c9f6413ad"),
            DDSpanId.from("5060571933882717101")),
        Arguments.of("48485a3953bb6124", "1", DD64bTraceId.fromHex("48485a3953bb6124"), 1L),
        Arguments.of(repeat("f", 16), "1", DD64bTraceId.MAX, 1L),
        Arguments.of("1", repeat("f", 16), DD64bTraceId.ONE, DDSpanId.MAX));
  }

  @ParameterizedTest
  @MethodSource("extractIdsWhileRetainingOriginalStringArguments")
  void extractIdsWhileRetainingOriginalString(
      String traceId, String spanId, DDTraceId expectedTraceId, long expectedSpanId) {
    String paddedTraceId = padLeft(traceId, 16, '0');
    String paddedSpanId = padLeft(spanId, 16, '0');
    Map<String, String> headers =
        Collections.singletonMap(
            "X-Amzn-Trace-Id",
            "Root=1-00000000-00000000" + paddedTraceId + ";Parent=" + paddedSpanId);

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(expectedTraceId, context.getTraceId());
    assertEquals(paddedLeft(traceId, 16, '0'), context.getTraceId().toHexStringPadded(16));
    assertEquals(expectedSpanId, context.getSpanId());
    assertEquals(paddedLeft(spanId, 16, '0'), DDSpanId.toHexStringPadded(context.getSpanId()));
  }

  static Stream<Arguments> extractHttpHeadersWithEndToEndArguments() {
    return Stream.of(Arguments.of("1", "2", 0L), Arguments.of("2", "3", 1610001234L));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersWithEndToEndArguments")
  void extractHttpHeadersWithEndToEnd(String traceId, String spanId, long endToEndStartTime) {
    Map<String, String> ctx =
        Collections.singletonMap(
            "X-Amzn-Trace-Id",
            "Root=1-00000000-00000000"
                + padLeft(traceId, 16, '0')
                + ";Parent="
                + padLeft(spanId, 16, '0')
                + ";k1=v1;t0="
                + endToEndStartTime
                + ";k2=v2");

    ExtractedContext context =
        (ExtractedContext) extractor.extract(ctx, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(endToEndStartTime * 1000000L, context.getEndToEndStartTime());
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

  // Pad a string on the left with a given character up to a given length
  static String padLeft(String s, int length, char pad) {
    if (s.length() >= length) {
      return s;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = s.length(); i < length; i++) {
      sb.append(pad);
    }
    sb.append(s);
    return sb.toString();
  }

  // Return the padded string (same as padLeft)
  static String paddedLeft(String s, int length, char pad) {
    return padLeft(s, length, pad);
  }

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
