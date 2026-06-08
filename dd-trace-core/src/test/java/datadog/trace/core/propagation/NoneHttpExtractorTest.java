package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@WithConfig(key = "propagation.extract.log_header_names.enabled", value = "true")
class NoneHttpExtractorTest extends DDJavaSpecification {

  private static final String SOME_HEADER = "SOME_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER = "SOME_CUSTOM_BAGGAGE_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER_2 = "SOME_CUSTOM_BAGGAGE_HEADER_2";
  private static final String SOME_TAG = "some-tag";
  private static final String SOME_BAGGAGE = "some-baggage";
  private static final String SOME_CASE_SENSITIVE_BAGGAGE = "some-CaseSensitive-baggage";

  private DynamicConfig<DynamicConfig.Snapshot> dynamicConfig;
  private HttpCodec.Extractor lazyExtractor;
  private boolean origAppSecActive;

  @BeforeEach
  void setup() {
    Map<String, String> baggageMap = new LinkedHashMap<>();
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER, SOME_BAGGAGE);
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER_2, SOME_CASE_SENSITIVE_BAGGAGE);
    dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG))
            .setBaggageMapping(baggageMap)
            .apply();
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void teardown() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive;
    extractor().cleanup();
  }

  private HttpCodec.Extractor extractor() {
    if (lazyExtractor == null) {
      lazyExtractor = createExtractor();
    }
    return lazyExtractor;
  }

  private HttpCodec.Extractor createExtractor() {
    return NoneCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
  }

  @TableTest({
    "scenario           | traceId                | spanId                 | samplingPriority       | origin | allowComma",
    "no origin comma    | '1'                    | '2'                    | PrioritySampling.UNSET |        | true      ",
    "no origin no comma | '2'                    | '3'                    | PrioritySampling.UNSET |        | false     ",
    "uint64 max comma   | '18446744073709551615' | '18446744073709551614' | PrioritySampling.UNSET |        | true      ",
    "uint64 max-1       | '18446744073709551614' | '18446744073709551615' | PrioritySampling.UNSET |        | false     "
  })
  void extractHttpHeaders(
      String traceId,
      String spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin,
      boolean allowComma) {
    injectEnvConfig(
        "DD_TRACE_REQUEST_HEADER_TAGS_COMMA_ALLOWED", String.valueOf(allowComma), false);
    HttpCodec.Extractor extractor = createExtractor();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info,and-more");
    headers.put(SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info");
    headers.put(SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2");
    if (samplingPriority != UNSET) {
      headers.put(SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority));
    }
    if (origin != null) {
      headers.put(ORIGIN_KEY, origin);
    }
    String expectedTagValue = allowComma ? "my-interesting-info,and-more" : "my-interesting-info";

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    Map<String, String> expectedBaggage = new LinkedHashMap<>();
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, expectedTagValue), context.getTags());
    assertEquals(samplingPriority, context.getSamplingPriority());
    assertEquals(origin, asString(context.getOrigin()));
    extractor.cleanup();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void extractHeaderTagsWithNoPropagation(boolean withOrigin) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (withOrigin) {
      headers.put(ORIGIN_KEY, "my-origin");
    }
    headers.put(SOME_HEADER, "my-interesting-info");

    TagContext context = extractor().extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    assertNull(context.getOrigin());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    String forwarded = "for=" + forwardedIp + ":" + forwardedPort;
    Map<String, String> tagOnlyCtx = singletonMap("Forwarded", forwarded);
    Map<String, String> fullCtx = new LinkedHashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("Forwarded", forwarded);

    TagContext context = extractor().extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwarded, context.getForwarded());

    context = extractor().extract(fullCtx, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    assertEquals(forwarded, context.getForwarded());
  }

  @Test
  void extractHeadersWithXForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    Map<String, String> tagOnlyCtx = new LinkedHashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", forwardedIp);
    tagOnlyCtx.put("X-Forwarded-Port", forwardedPort);
    Map<String, String> fullCtx = new LinkedHashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("x-forwarded-for", forwardedIp);
    fullCtx.put("x-forwarded-port", forwardedPort);

    TagContext context = extractor().extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());

    context = extractor().extract(fullCtx, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(0L, context.getSpanId());
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());
  }

  @Test
  void extractEmptyHeadersReturnsNull() {
    assertNull(
        extractor().extract(singletonMap("ignored-header", "ignored-value"), stringValuesMap()));
  }

  @Test
  @WithConfig(key = TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED, value = "false")
  void extractHeadersWithIpResolutionDisabled() {
    Map<String, String> tagOnlyCtx = new LinkedHashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("User-agent", "foo/bar");

    TagContext context = extractor().extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertNull(context.getXForwardedFor());
    assertEquals("foo/bar", context.getUserAgent());
  }

  @Test
  void extractHeadersWithIpResolutionDisabledAppsecDisabled() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    Map<String, String> tagOnlyCtx = new LinkedHashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("User-agent", "foo/bar");

    TagContext context = extractor().extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertNull(context.getXForwardedFor());
  }

  @Test
  @WithConfig(key = TracerConfig.TRACE_CLIENT_IP_HEADER, value = "my-header")
  void customIpHeaderCollectionDoesNotDisableStandardIpHeaderCollection() {
    Map<String, String> tagOnlyCtx = new LinkedHashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("My-Header", "8.8.8.8");

    TagContext context = extractor().extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertEquals("::1", context.getXForwardedFor());
    assertEquals("8.8.8.8", context.getCustomIpHeader());
  }

  @TableTest({
    "scenario            | hexId                             ",
    "64-bit short        | '1'                               ",
    "64-bit max chars    | '123456789abcdef0'                ",
    "128-bit             | '123456789abcdef0123456789abcdef0'",
    "128-bit zero middle | '64184f2400000000123456789abcdef0'",
    "128-bit all f       | 'ffffffffffffffffffffffffffffffff'"
  })
  void extractHttpHeadersWith128BitTraceId(String hexId) {
    DD128bTraceId traceId = DD128bTraceId.fromHex(hexId);
    boolean is128bTrace = traceId.toHighOrderLong() != 0;
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId.toString());
    headers.put(SPAN_ID_KEY.toUpperCase(), "2");
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info");
    if (is128bTrace) {
      headers.put(
          DATADOG_TAGS_KEY.toUpperCase(),
          "_dd.p.tid=" + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16));
    }

    TagContext context = extractor().extract(headers, stringValuesMap());

    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    assertTrue(context.getBaggage().isEmpty());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "traceId");
    headers.put(SPAN_ID_KEY.toUpperCase(), "spanId");
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info");

    TagContext context = extractor().extract(headers, stringValuesMap());

    assertInstanceOf(TagContext.class, context);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractCommonHttpHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
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

    TagContext context = extractor().extract(headers, stringValuesMap());

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

  private static String asString(CharSequence cs) {
    return cs == null ? null : cs.toString();
  }
}
