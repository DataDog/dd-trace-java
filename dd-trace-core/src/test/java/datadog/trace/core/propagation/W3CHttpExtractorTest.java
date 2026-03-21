package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.SamplingMechanism;
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

class W3CHttpExtractorTest extends DDCoreSpecification {

  static final String TEST_TP_DROP = "00-00000000000000000000000000000001-123456789abcdef0-00";
  static final String TEST_TP_KEEP = "00-00000000000000000000000000000001-123456789abcdef0-01";
  static final long TEST_SPAN_ID = 1311768467463790320L;
  static final DDTraceId TRACE_ID_ONE = DDTraceId.fromHex("00000000000000000000000000000001");
  static final DDTraceId TRACE_ID_NO_HIGH_LOW_MAX =
      DDTraceId.fromHex("0000000000000000ffffffffffffffff");
  static final DDTraceId TRACE_ID_LOW_MAX = DDTraceId.fromHex("123456789abcdef0ffffffffffffffff");

  DynamicConfig dynamicConfig;
  HttpCodec.Extractor _extractor;
  boolean origAppSecActive;

  HttpCodec.Extractor getExtractor() {
    if (_extractor == null) {
      _extractor =
          W3CHttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
    }
    return _extractor;
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
  }

  static Stream<Arguments> extractTraceparentArguments() {
    return Stream.of(
        Arguments.of(null, false, null, null, null),
        Arguments.of(
            "00-00000000000000000000000000000000-123456789abcdef0-01", false, null, null, null),
        Arguments.of(
            "00-123456789abcdef00000000000000000-123456789abcdef0-01", false, null, null, null),
        Arguments.of(
            "00-00000000000000000000000000000001-0000000000000000-01", false, null, null, null),
        Arguments.of(
            "00-00000000000000000000000000000001-123456789abcdef0-01",
            true,
            TRACE_ID_ONE,
            TEST_SPAN_ID,
            (int) SAMPLER_KEEP),
        Arguments.of(
            "\t00-00000000000000000000000000000001-123456789abcdef0-01",
            true,
            TRACE_ID_ONE,
            TEST_SPAN_ID,
            (int) SAMPLER_KEEP),
        Arguments.of(
            "00-00000000000000000000000000000001-123456789abcdef0-01\t",
            true,
            TRACE_ID_ONE,
            TEST_SPAN_ID,
            (int) SAMPLER_KEEP),
        Arguments.of(
            " 00-00000000000000000000000000000001-123456789abcdef0-01 ",
            true,
            TRACE_ID_ONE,
            TEST_SPAN_ID,
            (int) SAMPLER_KEEP),
        Arguments.of(
            "00-0000000000000000ffffffffffffffff-ffffffffffffffff-01",
            true,
            TRACE_ID_NO_HIGH_LOW_MAX,
            DDSpanId.MAX,
            (int) SAMPLER_KEEP),
        Arguments.of(
            "00-0000000000000000ffffffffffffffff-ffffffffffffffff-00",
            true,
            TRACE_ID_NO_HIGH_LOW_MAX,
            DDSpanId.MAX,
            (int) SAMPLER_DROP),
        Arguments.of(
            "00-123456789abcdef0ffffffffffffffff-123456789abcdef0-00",
            true,
            TRACE_ID_LOW_MAX,
            TEST_SPAN_ID,
            (int) SAMPLER_DROP),
        Arguments.of(
            "00-123456789abcdef0ffffffffffffffFf-123456789abcdef0-00", false, null, null, null),
        Arguments.of(
            "00-123456789abcdeF0ffffffffffffffff-123456789abcdef0-00", false, null, null, null),
        Arguments.of(
            "00-123456789abcdef0fffffffffFffffff-123456789abcdef0-00", false, null, null, null),
        Arguments.of(
            "00-123456789abcdef0ffffffffffffffff-123456789Abcdef0-00", false, null, null, null),
        Arguments.of(
            "00-123456789\u00e4bcdef0ffffffffffffffff-123456789abcdef0-00",
            false,
            null,
            null,
            null),
        Arguments.of(
            "00-123456789abcdef0ffffffff\u00e4fffffff-123456789abcdef0-00",
            false,
            null,
            null,
            null),
        Arguments.of(
            "00-123456789abcdef0ffffffffffffffff-123456789\u00e4bcdef0-00",
            false,
            null,
            null,
            null),
        Arguments.of(
            "01-00000000000000000000000000000001-0000000000000001-02",
            true,
            TRACE_ID_ONE,
            1L,
            (int) SAMPLER_DROP),
        Arguments.of(
            "000-0000000000000000000000000000001-0000000000000001-01", false, null, null, null),
        Arguments.of(
            "00-0000000000000000000000000000001 -0000000000000001-01", false, null, null, null),
        Arguments.of(
            "00-0000000000000000000000000000001-0000000000000001-01", false, null, null, null),
        Arguments.of(
            "00-00000000000000000000000000000001-000000000000001-01", false, null, null, null),
        Arguments.of(
            "00-00000000000000000000000000000001-0000000000000001-0", false, null, null, null),
        Arguments.of(
            "ff-00000000000000000000000000000001-0000000000000001-00", false, null, null, null),
        Arguments.of(
            "fe-00000000000000000000000000000001-0000000000000001-02",
            true,
            TRACE_ID_ONE,
            1L,
            (int) SAMPLER_DROP),
        Arguments.of(
            "00-00000000000000000000000000000001-0000000000000001-03-0", false, null, null, null),
        Arguments.of(
            "fe-00000000000000000000000000000001-0000000000000001-02.0", false, null, null, null));
  }

  @ParameterizedTest
  @MethodSource("extractTraceparentArguments")
  void extractTraceparent(
      String traceparent, boolean tpValid, DDTraceId traceId, Object spanId, Integer priority) {
    Map<String, String> headers = new HashMap<>();
    if (traceparent != null) {
      headers.put(W3CHttpCodec.TRACE_PARENT_KEY, traceparent);
    }

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    if (tpValid) {
      assertNotNull(context);
      assertEquals(traceId, context.getTraceId());
      if (spanId instanceof Long) {
        assertEquals((long) (Long) spanId, context.getSpanId());
      }
      assertEquals((int) priority, context.getSamplingPriority());
    } else {
      assertNull(context);
    }
  }

  @Test
  void checkMaxFromW3CTraceIds() {
    assertEquals(DD64bTraceId.MAX.toLong(), TRACE_ID_LOW_MAX.toLong());
    assertEquals(DD64bTraceId.MAX.toLong(), TRACE_ID_NO_HIGH_LOW_MAX.toLong());
  }

  static Stream<Arguments> extractTraceparentTracestateAndHttpHeadersArguments() {
    return Stream.of(
        Arguments.of(TEST_TP_KEEP, "", (int) SAMPLER_KEEP, (int) SamplingMechanism.DEFAULT, null),
        Arguments.of(TEST_TP_DROP, "", (int) SAMPLER_DROP, null, null),
        Arguments.of(TEST_TP_KEEP, "dd=s:2;o:some", (int) USER_KEEP, null, "some"),
        Arguments.of(
            TEST_TP_KEEP,
            "dd=s:2;o:some;t.dm:-4",
            (int) USER_KEEP,
            (int) SamplingMechanism.MANUAL,
            "some"),
        Arguments.of(TEST_TP_DROP, "dd=s:2;o:some;t.dm:-4", (int) SAMPLER_DROP, null, "some"),
        Arguments.of(TEST_TP_DROP, "dd=s:-1;o:some", (int) USER_DROP, null, "some"),
        Arguments.of(
            TEST_TP_DROP,
            "dd=s:-1;o:some;t.dm:-4",
            (int) USER_DROP,
            (int) SamplingMechanism.MANUAL,
            "some"),
        Arguments.of(
            TEST_TP_KEEP,
            "dd=s:-1;o:some;t.dm:-4",
            (int) SAMPLER_KEEP,
            (int) SamplingMechanism.DEFAULT,
            "some"));
  }

  @ParameterizedTest
  @MethodSource("extractTraceparentTracestateAndHttpHeadersArguments")
  void extractTraceparentTracestateAndHttpHeaders(
      String traceparent, String tracestate, int priority, Integer decisionMaker, String origin) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_PARENT_KEY.toUpperCase(), traceparent);
    headers.put(TRACE_STATE_KEY.toUpperCase(), tracestate);
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "my-interesting-baggage-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "my-interesting-baggage-info-2");

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(TRACE_ID_ONE, context.getTraceId());
    assertEquals(TEST_SPAN_ID, context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(priority, context.getSamplingPriority());
    if (decisionMaker != null) {
      assertEquals(
          Collections.singletonMap("_dd.p.dm", "-" + decisionMaker),
          context.getPropagationTags().createTagMap());
    } else {
      assertEquals(
          Collections.<String, String>emptyMap(), context.getPropagationTags().createTagMap());
    }
    if (origin != null) {
      assertEquals(origin, context.getOrigin().toString());
    }
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    Map<String, String> headers = Collections.singletonMap("SOME_HEADER", "my-interesting-info");
    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());
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
    TagContext context = getExtractor().extract(tagOnlyCtx, ContextVisitors.stringValuesMap());
    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwardedValue, context.getForwarded());

    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(
        TRACE_PARENT_KEY.toUpperCase(), "00-00000000000000000000000000000001-0000000000000002-01");
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
    fullCtx.put(
        TRACE_PARENT_KEY.toUpperCase(), "00-00000000000000000000000000000001-0000000000000002-01");
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

  static Stream<Arguments> extractHttpHeadersWithEndToEndArguments() {
    return Stream.of(Arguments.of(0L), Arguments.of(1610001234L));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersWithEndToEndArguments")
  void extractHttpHeadersWithEndToEnd(long endToEndStartTime) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(
        TRACE_PARENT_KEY.toUpperCase(), "00-00000000000000000000000000000001-123456789abcdef0-01");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "t0", String.valueOf(endToEndStartTime));
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_HEADER", "my-interesting-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "my-interesting-baggage-info");
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER_2", "my-interesting-baggage-info-2");

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(TRACE_ID_ONE, context.getTraceId());
    assertEquals(TEST_SPAN_ID, context.getSpanId());
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
    return Stream.of(
        Arguments.of(false, "00-00000000000000000000000000000000-123456789abcdef0-01"),
        Arguments.of(false, "00-00000000000000000000000000000001-0000000000000000-01"),
        Arguments.of(true, "00-00000000000000000000000000000001-0000000000000001-01"));
  }

  @ParameterizedTest
  @MethodSource("baggageIsMappedOnContextCreationArguments")
  void baggageIsMappedOnContextCreation(boolean tpValid, String traceparent) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_PARENT_KEY, traceparent);
    headers.put("SOME_CUSTOM_BAGGAGE_HEADER", "mappedBaggageValue");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k1", "v1");
    headers.put(OT_BAGGAGE_PREFIX.toUpperCase() + "k2", "v2");
    headers.put("SOME_ARBITRARY_HEADER", "my-interesting-info");

    TagContext context = getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    if (tpValid) {
      assertEquals(TRACE_ID_ONE, context.getTraceId());
      assertEquals(1L, context.getSpanId());
    }
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("some-baggage", "mappedBaggageValue");
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
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

  static Stream<Arguments> markInconsistentTidAsPropagationErrorArguments() {
    return Stream.of(
        Arguments.of("00-123456789abcdef00fedcba987654321-123456789abcdef0-01", "", true),
        Arguments.of(
            "00-123456789abcdef00fedcba987654321-123456789abcdef0-01",
            "dd=t.tid:123456789abcdef0",
            true),
        Arguments.of(
            "00-123456789abcdef00fedcba987654321-123456789abcdef0-01",
            "dd=t.tid:123456789abcdef1",
            false));
  }

  @ParameterizedTest
  @MethodSource("markInconsistentTidAsPropagationErrorArguments")
  void markInconsistentTidAsPropagationError(
      String traceparent, String tracestate, boolean consistent) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_PARENT_KEY.toUpperCase(), traceparent);
    headers.put(TRACE_STATE_KEY.toUpperCase(), tracestate);

    ExtractedContext context =
        (ExtractedContext) getExtractor().extract(headers, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    Map<String, String> defaultTags = new HashMap<>();
    defaultTags.put("_dd.p.dm", "-0");
    defaultTags.put("_dd.p.tid", "123456789abcdef0");
    Map<String, String> expectedTags;
    if (consistent) {
      expectedTags = defaultTags;
    } else {
      String tid = tracestate.isEmpty() ? "" : tracestate.substring(9);
      expectedTags = new HashMap<>(defaultTags);
      expectedTags.put("_dd.propagation_error", "inconsistent_tid " + tid);
    }
    assertEquals(expectedTags, context.getPropagationTags().createTagMap());
  }
}
