package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_SPAN_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY;
import static java.math.BigInteger.ONE;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
class HaystackHttpExtractorTest extends DDJavaSpecification {

  private static final String SOME_HEADER = "SOME_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER = "SOME_CUSTOM_BAGGAGE_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER_2 = "SOME_CUSTOM_BAGGAGE_HEADER_2";
  private static final String SOME_ARBITRARY_HEADER = "SOME_ARBITRARY_HEADER";
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
    this.extractor =
        HaystackHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void teardown() {
    this.extractor.cleanup();
    APPSEC_ACTIVE = origAppSecActive;
  }

  @TableTest({
    "scenario         | traceId                | spanId                 | traceUuid                              | spanUuid                              ",
    "small ids        | '1'                    | '2'                    | '44617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "incrementing ids | '2'                    | '3'                    | '44617461-646f-6721-0000-000000000002' | '44617461-646f-6721-0000-000000000003'",
    "uint64 max       | '18446744073709551615' | '18446744073709551609' | '44617461-646f-6721-ffff-ffffffffffff' | '44617461-646f-6721-ffff-fffffffffff9'",
    "uint64 max-1     | '18446744073709551614' | '18446744073709551608' | '44617461-646f-6721-ffff-fffffffffffe' | '44617461-646f-6721-ffff-fffffffffff8'"
  })
  void extractHttpHeaders(String traceId, String spanId, String traceUuid, String spanUuid) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceUuid);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanUuid);
    headers.put((OT_BAGGAGE_PREFIX + "k1").toUpperCase(), "v1");
    headers.put((OT_BAGGAGE_PREFIX + "k2").toUpperCase(), "%76%32"); // v2 encoded once
    headers.put((OT_BAGGAGE_PREFIX + "k3").toUpperCase(), "%25%37%36%25%33%33"); // v3 encoded twice
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
    expectedBaggage.put("k3", "%76%33"); // expect value decoded only once
    expectedBaggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, traceUuid);
    expectedBaggage.put(HAYSTACK_SPAN_ID_BAGGAGE_KEY, spanUuid);
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    assertEquals(SAMPLER_KEEP, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    Map<String, String> headers = singletonMap(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "123";
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
    String forwardedPort = "123";
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
    Map<String, String> headers = singletonMap("ignored-header", "ignored-value");
    assertNull(this.extractor.extract(headers, stringValuesMap()));
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
    "scenario       | traceId                                | spanId                                 | ctxCreated",
    "negative trace | '-1'                                   | '1'                                    | false     ",
    "negative span  | '1'                                    | '-1'                                   | false     ",
    "zero traceId   | '0'                                    | '1'                                    | true      ",
    "uuid format    | '44617461-646f-6721-463a-c35c9f6413ad' | '44617461-646f-6721-463a-c35c9f6413ad' | true      "
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
      expectedBaggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, traceId);
      expectedBaggage.put(HAYSTACK_SPAN_ID_BAGGAGE_KEY, spanId);
      expectedBaggage.put(SOME_BAGGAGE, "mappedBaggageValue");
      expectedBaggage.put("k1", "v1");
      expectedBaggage.put("k2", "v2");
      assertEquals(expectedBaggage, context.getBaggage());
    } else {
      assertNull(context);
    }
  }

  @TableTest({
    "scenario               | traceId                                | spanId                                 | expectedTraceIdLong | expectedSpanId      | ctxCreated",
    "negative traceId       | '-1'                                   | '1'                                    |                     | 0                   | false     ",
    "negative spanId        | '1'                                    | '-1'                                   |                     | 0                   | false     ",
    "zero traceId           | '0'                                    | '1'                                    |                     | 0                   | true      ",
    "padded ones            | '00001'                                | '00001'                                | 1                   | 1                   | true      ",
    "64-bit hex             | '463ac35c9f6413ad'                     | '463ac35c9f6413ad'                     | 5060571933882717101 | 5060571933882717101 | true      ",
    "128-bit hex truncated  | '463ac35c9f6413ad48485a3953bb6124'     | '1'                                    | 5208512171318403364 | 1                   | true      ",
    "uuid format same       | '44617461-646f-6721-463a-c35c9f6413ad' | '44617461-646f-6721-463a-c35c9f6413ad' | 5060571933882717101 | 5060571933882717101 | true      ",
    "uint64 max 64-bit      | 'ffffffffffffffff'                     | '1'                                    | -1                  | 1                   | true      ",
    "128-bit high+low max   | 'aaaaaaaaaaaaaaaaffffffffffffffff'     | '1'                                    | -1                  | 1                   | true      ",
    "traceId too long high1 | '1ffffffffffffffffffffffffffffffff'    | '1'                                    |                     | 1                   | false     ",
    "traceId too long high0 | '0ffffffffffffffffffffffffffffffff'    | '1'                                    |                     | 1                   | false     ",
    "uint64 max spanId      | '1'                                    | 'ffffffffffffffff'                     | 1                   | -1                  | true      ",
    "spanId too long        | '1'                                    | '1ffffffffffffffff'                    |                     | 0                   | false     ",
    "padded uint64 max span | '1'                                    | '000ffffffffffffffff'                  | 1                   | -1                  | true      "
  })
  void extract128BitIdTruncatesIdTo64Bit(
      String traceId,
      String spanId,
      Long expectedTraceIdLong,
      long expectedSpanId,
      boolean ctxCreated) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (expectedTraceIdLong != null) {
      assertEquals(DDTraceId.from(expectedTraceIdLong), context.getTraceId());
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
