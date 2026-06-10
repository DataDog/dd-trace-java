package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS_COMMA_ALLOWED;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
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
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.converter.TraceIdConverter;
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
class NoneHttpExtractorTest extends DDJavaSpecification {
  private static final String SOME_HEADER = "SOME_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER = "SOME_CUSTOM_BAGGAGE_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER_2 = "SOME_CUSTOM_BAGGAGE_HEADER_2";
  private static final String SOME_TAG = "some-tag";
  private static final String SOME_BAGGAGE = "some-baggage";
  private static final String SOME_CASE_SENSITIVE_BAGGAGE = "some-CaseSensitive-baggage";

  private HttpCodec.Extractor extractor;
  private boolean origAppSecActive;

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
    this.extractor = NoneCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void teardown() {
    APPSEC_ACTIVE = origAppSecActive;
    this.extractor.cleanup();
  }

  @TableTest({
    "scenario     | traceId          | spanId          ",
    "no origin    | '1'              | '2'             ",
    "uint64 max   | 'MAX'   | 'MAX-1'",
    "uint64 max-1 | 'MAX-1' | 'MAX'  "
  })
  void extractHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId,
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info,and-more",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info,and-more"), context.getTags());
    assertEquals(UNSET, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  @WithConfig(key = REQUEST_HEADER_TAGS_COMMA_ALLOWED, value = "false")
  @Test
  void extractHttpHeadersWithoutComma() {
    // Recreate extractor with the comma-disallowed config
    this.extractor.cleanup();
    Map<String, String> baggageMap = new HashMap<>();
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER, SOME_BAGGAGE);
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER_2, SOME_CASE_SENSITIVE_BAGGAGE);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG))
            .setBaggageMapping(baggageMap)
            .apply();
    this.extractor = NoneCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, "2",
        SPAN_ID_KEY, "3",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info,and-more",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void extractHeaderTagsWithNoPropagation(boolean withOrigin) {
    // spotless:off
    Map<String, String> headers = headers(
        ORIGIN_KEY, withOrigin ? "my-origin" : null,
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    assertNull(context.getOrigin());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    String forwarded = "for=" + forwardedIp + ":" + forwardedPort;
    Map<String, String> tagOnlyCtx = headers("Forwarded", forwarded);
    // spotless:off
    Map<String, String> fullCtx = headers(
        TRACE_ID_KEY, "1",
        SPAN_ID_KEY, "2",
        "Forwarded", forwarded
    );
    // spotless:on

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwarded, context.getForwarded());

    context = this.extractor.extract(fullCtx, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    assertEquals(forwarded, context.getForwarded());
  }

  @Test
  void extractHeadersWithXForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    // spotless:off
    Map<String, String> tagOnlyCtx = headers(
        "X-Forwarded-For", forwardedIp,
        "X-Forwarded-Port", forwardedPort
    );
    Map<String, String> fullCtx = headers(
        TRACE_ID_KEY, "1",
        SPAN_ID_KEY, "2",
        "x-forwarded-for", forwardedIp,
        "x-forwarded-port", forwardedPort
    );
    // spotless:on

    TagContext context = this.extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertFalse(context instanceof ExtractedContext);
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());

    context = this.extractor.extract(fullCtx, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(0L, context.getSpanId());
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());
  }

  @Test
  void extractEmptyHeadersReturnsNull() {
    assertNull(
        this.extractor.extract(headers("ignored-header", "ignored-value"), stringValuesMap()));
  }

  @Test
  @WithConfig(key = TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED, value = "false")
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
  @WithConfig(key = TracerConfig.TRACE_CLIENT_IP_HEADER, value = "my-header")
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
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId.toString(),
        SPAN_ID_KEY, "2",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info",
        DATADOG_TAGS_KEY, is128bTrace
            ? "_dd.p.tid=" + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16)
            : null
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.ZERO, context.getTraceId());
    assertEquals(DDSpanId.ZERO, context.getSpanId());
    assertTrue(context.getBaggage().isEmpty());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, "traceId",
        SPAN_ID_KEY,"spanId",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertInstanceOf(TagContext.class, context);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractCommonHttpHeaders() {
    // spotless:off
    Map<String, String> headers = headers(
        HttpCodec.USER_AGENT_KEY, "some-user-agent",
        HttpCodec.X_CLUSTER_CLIENT_IP_KEY, "1.1.1.1",
        HttpCodec.X_REAL_IP_KEY, "2.2.2.2",
        HttpCodec.X_CLIENT_IP_KEY, "3.3.3.3",
        HttpCodec.TRUE_CLIENT_IP_KEY, "4.4.4.4",
        HttpCodec.FORWARDED_FOR_KEY, "5.5.5.5",
        HttpCodec.FORWARDED_KEY, "6.6.6.6",
        HttpCodec.FASTLY_CLIENT_IP_KEY, "7.7.7.7",
        HttpCodec.CF_CONNECTING_IP_KEY, "8.8.8.8",
        HttpCodec.CF_CONNECTING_IP_V6_KEY, "9.9.9.9");
    // spotless:on

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
}
