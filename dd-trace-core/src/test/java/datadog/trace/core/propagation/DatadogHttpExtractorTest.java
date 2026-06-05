package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS_COMMA_ALLOWED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static java.math.BigInteger.ONE;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
class DatadogHttpExtractorTest extends DDJavaSpecification {
  private static final String SOME_HEADER = "SOME_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER = "SOME_CUSTOM_BAGGAGE_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER_2 = "SOME_CUSTOM_BAGGAGE_HEADER_2";
  private static final String SOME_ARBITRARY_HEADER = "SOME_ARBITRARY_HEADER";
  private static final String SOME_TAG = "some-tag";
  private static final String SOME_BAGGAGE = "some-baggage";
  private static final String SOME_CASE_SENSITIVE_BAGGAGE = "some-CaseSensitive-baggage";

  private boolean origAppSecActive;
  private HttpCodec.Extractor extractor;

  @BeforeEach
  void setup() {
    Map<String, String> baggageMap = new HashMap<>();
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER, SOME_BAGGAGE);
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER_2, SOME_CASE_SENSITIVE_BAGGAGE);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG))
            .setBaggageMapping(baggageMap)
            .apply();
    this.extractor = DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void teardown() {
    this.extractor.cleanup();
    APPSEC_ACTIVE = this.origAppSecActive;
  }

  @TableTest({
    "scenario          | traceId                | spanId                 | samplingPriority              | origin  ",
    "unset no origin   | '1'                    | '2'                    | PrioritySampling.UNSET        |         ",
    "keep with origin  | '2'                    | '3'                    | PrioritySampling.SAMPLER_KEEP | 'saipan'",
    "uint64 max unset  | '18446744073709551615' | '18446744073709551614' | PrioritySampling.UNSET        | 'saipan'",
    "uint64 max-1 keep | '18446744073709551614' | '18446744073709551615' | PrioritySampling.SAMPLER_KEEP | 'saipan'"
  })
  void extractHttpHeaders(
      String traceId,
      String spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin) {
    Map<String, String> headers = new HashMap<>();
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

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info,and-more"), context.getTags());
    assertEquals(samplingPriority, context.getSamplingPriority());
    assertEquals(origin, asString(context.getOrigin()));
  }

  @WithConfig(key = REQUEST_HEADER_TAGS_COMMA_ALLOWED, value = "false")
  @Test
  void extractHttpHeadersWithoutComma() {
    // Recreate extractor with the new comma config
    this.extractor.cleanup();
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG)).apply();
    this.extractor = DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "1");
    headers.put(SPAN_ID_KEY.toUpperCase(), "2");
    String headerWithComma = "my-interesting-info,and-more";
    headers.put(SOME_HEADER, headerWithComma);

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    String expectedHeader = "my-interesting-info";
    assertEquals(expectedHeader, context.getTags().getString(SOME_TAG));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void extractHeaderTagsWithNoPropagation(boolean withOrigin) {
    Map<String, String> headers = new HashMap<>();
    if (withOrigin) {
      headers.put(ORIGIN_KEY, "my-origin");
    }
    headers.put(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    if (withOrigin) {
      assertEquals("my-origin", asString(context.getOrigin()));
    }
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    String forwarded = "for=" + forwardedIp + ":" + forwardedPort;
    Map<String, String> tagOnlyCtx = singletonMap("Forwarded", forwarded);
    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("Forwarded", forwarded);

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwarded, context.getForwarded());

    context = this.extractor.extract(fullCtx, stringValuesMap());

    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwarded, context.getForwarded());
  }

  @Test
  void extractHeadersWithXForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", forwardedIp);
    tagOnlyCtx.put("X-Forwarded-Port", forwardedPort);
    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("x-forwarded-for", forwardedIp);
    fullCtx.put("x-forwarded-port", forwardedPort);

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());

    context = this.extractor.extract(fullCtx, stringValuesMap());

    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());
  }

  @Test
  void extractEmptyHeadersReturnsNull() {
    Map<String, String> carrier = singletonMap("ignored-header", "ignored-value");
    assertNull(this.extractor.extract(carrier, stringValuesMap()));
  }

  @Test
  @WithConfig(key = TRACE_CLIENT_IP_RESOLVER_ENABLED, value = "false")
  void extractHeadersWithIpResolutionDisabled() {
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("User-agent", "foo/bar");

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertNull(context.getXForwardedFor());
    assertEquals("foo/bar", context.getUserAgent());
  }

  @Test
  void extractHeadersWithIpResolutionDisabledAppsecDisabled() {
    APPSEC_ACTIVE = false;
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("User-agent", "foo/bar");

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertNull(context.getXForwardedFor());
  }

  @Test
  @WithConfig(key = TRACE_CLIENT_IP_HEADER, value = "my-header")
  void customIpHeaderCollectionDoesNotDisableStandardIpHeaderCollection() {
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", "::1");
    tagOnlyCtx.put("My-Header", "8.8.8.8");

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

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

    Map<String, String> headers = new HashMap<>();
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

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    DDTraceId expectedTraceId = is128bTrace ? traceId : DD64bTraceId.from(traceId.toLong());
    assertEquals(expectedTraceId, context.getTraceId());
    assertEquals(DDSpanId.from("2"), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "traceId");
    headers.put(SPAN_ID_KEY.toUpperCase(), "spanId");
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithOutOfRangeTraceId() {
    String outOfRangeTraceId = TRACE_ID_MAX.add(ONE).toString();
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), outOfRangeTraceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), "0");
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithOutOfRangeSpanId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "0");
    headers.put(SPAN_ID_KEY.toUpperCase(), "-1");
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @TableTest({
    "scenario             | traceId                | spanId                 | expectExtraction",
    "negative traceId     | '-1'                   | '1'                    | false           ",
    "negative spanId      | '1'                    | '-1'                   | false           ",
    "zero traceId         | '0'                    | '1'                    | false           ",
    "zero spanId          | '1'                    | '0'                    | true            ",
    "uint64 max traceId   | '18446744073709551615' | '1'                    | true            ",
    "out-of-range traceId | '18446744073709551616' | '1'                    | false           ",
    "uint64 max spanId    | '1'                    | '18446744073709551615' | true            ",
    "out-of-range spanId  | '1'                    | '18446744073709551616' | false           "
  })
  void moreIdRangeValidation(String traceId, String spanId, boolean expectExtraction) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (expectExtraction) {
      ExtractedContext extracted = assertInstanceOf(ExtractedContext.class, context);
      assertEquals(DDTraceId.from(traceId), extracted.getTraceId());
      assertEquals(DDSpanId.from(spanId), extracted.getSpanId());
    } else {
      assertNull(context);
    }
  }

  @TableTest({
    "scenario   | traceId | spanId | endToEndStartTime",
    "zero       | '1'     | '2'    | 0                ",
    "epoch 2021 | '2'     | '3'    | 1610001234       "
  })
  void extractHttpHeadersWithEndToEnd(String traceId, String spanId, long endToEndStartTime) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "t0").toUpperCase(), String.valueOf(endToEndStartTime));
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_HEADER, "my-interesting-info");
    headers.put(SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info");
    headers.put(SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2");

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    assertEquals(endToEndStartTime * 1000000L, context.getEndToEndStartTime());
  }

  @TableTest({
    "scenario         | traceId                | spanId                 | ctxCreated",
    "negative traceId | '-1'                   | '1'                    | false     ",
    "negative spanId  | '1'                    | '-1'                   | false     ",
    "zero traceId     | '0'                    | '1'                    | true      ",
    "uint64 max-1 ids | '18446744073709551614' | '18446744073709551614' | true      "
  })
  void baggageIsMappedOnContextCreation(String traceId, String spanId, boolean ctxCreated) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    headers.put(SOME_CUSTOM_BAGGAGE_HEADER, "mappedBaggageValue");
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "v2");
    headers.put(SOME_ARBITRARY_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (ctxCreated) {
      assertNotNull(context);
      Map<String, String> expectedBaggage = new HashMap<>();
      expectedBaggage.put(SOME_BAGGAGE, "mappedBaggageValue");
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

    TagContext context = this.extractor.extract(headers, stringValuesMap());

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
