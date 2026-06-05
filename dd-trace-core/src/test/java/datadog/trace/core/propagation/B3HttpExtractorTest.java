package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.B3_SPAN_ID;
import static datadog.trace.core.propagation.B3HttpCodec.B3_TRACE_ID;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_ACCEPT;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

@WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
class B3HttpExtractorTest extends DDJavaSpecification {
  private static final String SOME_HEADER = "SOME_HEADER";
  private static final String SOME_TAG = "some-tag";
  private static final String SOME_VALUE = "my-interesting-info";
  private static final String FORWARDED_IP = "1.2.3.4";
  private static final String FORWARDED_PORT = "1234";

  private HttpCodec.Extractor extractor;
  private boolean origAppSecActive;

  @BeforeEach
  void setup() {
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG))
            .setBaggageMapping(emptyMap())
            .apply();
    this.extractor = B3HttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);
    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void teardown() {
    APPSEC_ACTIVE = this.origAppSecActive;
  }

  @TableTest({
    "scenario          | traceIdHex         | spanIdHex          | samplingPriority | expectedSamplingPriority     ",
    "no priority       | '1'                | '2'                |                  | PrioritySampling.UNSET       ",
    "sampler keep      | '2'                | '3'                | 1                | PrioritySampling.SAMPLER_KEEP",
    "sampler drop      | '3'                | '4'                | 0                | PrioritySampling.SAMPLER_DROP",
    "uint64 max drop   | 'ffffffffffffffff' | 'fffffffffffffffe' | 0                | PrioritySampling.SAMPLER_DROP",
    "uint64 max-1 keep | 'fffffffffffffffe' | 'ffffffffffffffff' | 1                | PrioritySampling.SAMPLER_KEEP"
  })
  void extractHttpHeaders(
      String traceIdHex,
      String spanIdHex,
      Integer samplingPriority,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    Map<String, String> headers = new HashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceIdHex);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanIdHex);
    headers.put(SOME_HEADER, SOME_VALUE);
    if (samplingPriority != null) {
      headers.put(SAMPLING_PRIORITY_KEY, samplingPriority.toString());
    }

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(B3TraceId.fromHex(traceIdHex), context.getTraceId());
    assertEquals(DDSpanId.fromHex(spanIdHex), context.getSpanId());
    assertEquals(emptyMap(), context.getBaggage());
    assertEquals(expectedB3Tags(context), context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  @TableTest({
    "scenario              | b3      | expectedTraceIdHex | expectedSpanId | expectedSamplingPriority     ",
    "b3 takes precedence   | '2-3-0' | '2'                | 3              | PrioritySampling.SAMPLER_DROP",
    "b3 without priority   | '2-3'   | '2'                | 3              | PrioritySampling.UNSET       ",
    "invalid b3 falls back | '0'     | '1'                | 2              | PrioritySampling.SAMPLER_KEEP",
    "absent b3 falls back  |         | '1'                | 2              | PrioritySampling.SAMPLER_KEEP"
  })
  void extractHttpHeadersWithB3HeaderAtTheBeginning(
      String b3,
      String expectedTraceIdHex,
      long expectedSpanId,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    String traceIdHex = "1";
    String spanIdHex = "2";
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("", "empty key");
    headers.put(B3_KEY, b3);
    headers.put(TRACE_ID_KEY.toUpperCase(), traceIdHex);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanIdHex);
    headers.put(SOME_HEADER, SOME_VALUE);
    headers.put(SAMPLING_PRIORITY_KEY, SAMPLING_PRIORITY_ACCEPT);

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertB3MultiOrSingleContext(
        context, expectedTraceIdHex, expectedSpanId, expectedSamplingPriority);
  }

  @TableTest({
    "scenario              | b3      | expectedTraceIdHex | expectedSpanId | expectedSamplingPriority     ",
    "b3 takes precedence   | '2-3-0' | '2'                | 3              | PrioritySampling.SAMPLER_DROP",
    "b3 without priority   | '2-3'   | '2'                | 3              | PrioritySampling.UNSET       ",
    "invalid b3 falls back | '0'     | '1'                | 2              | PrioritySampling.SAMPLER_KEEP",
    "absent b3 falls back  |         | '1'                | 2              | PrioritySampling.SAMPLER_KEEP"
  })
  void extractHttpHeadersWithB3HeaderAtTheEnd(
      String b3,
      String expectedTraceIdHex,
      long expectedSpanId,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    String traceIdHex = "1";
    String spanIdHex = "2";
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("", "empty key");
    headers.put(TRACE_ID_KEY.toUpperCase(), traceIdHex);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanIdHex);
    headers.put(B3_KEY, b3);
    headers.put(SOME_HEADER, SOME_VALUE);
    headers.put(SAMPLING_PRIORITY_KEY, SAMPLING_PRIORITY_ACCEPT);

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertB3MultiOrSingleContext(
        context, expectedTraceIdHex, expectedSpanId, expectedSamplingPriority);
  }

  private void assertB3MultiOrSingleContext(
      TagContext context,
      String expectedTraceIdHex,
      long expectedSpanId,
      int expectedSamplingPriority) {
    assertEquals(B3TraceId.fromHex(expectedTraceIdHex), context.getTraceId());
    assertEquals(expectedSpanId, context.getSpanId());
    assertFalse(context.baggageItems().iterator().hasNext());
    assertEquals(expectedB3Tags(context), context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  private Map<String, Object> expectedB3Tags(TagContext context) {
    Map<String, Object> expected = new HashMap<>();
    expected.put(B3_TRACE_ID, ((B3TraceId) context.getTraceId()).getOriginal());
    expected.put(B3_SPAN_ID, DDSpanId.toHexString(context.getSpanId()));
    expected.put(SOME_TAG, SOME_VALUE);
    return expected;
  }

  @TableTest({
    "scenario               | traceId                             | spanId              | expectedTraceIdHex                 | expectedSpanId     ",
    "negative traceId       | '-1'                                | '1'                 |                                    |                    ",
    "negative spanId        | '1'                                 | '-1'                |                                    |                    ",
    "zero traceId           | '0'                                 | '1'                 |                                    |                    ",
    "padded traceId         | '00001'                             | '1'                 | '00001'                            | 1                  ",
    "64-bit ids             | '463ac35c9f6413ad'                  | '463ac35c9f6413ad'  | '463ac35c9f6413ad'                 | 5060571933882717101",
    "128-bit traceId        | '463ac35c9f6413ad48485a3953bb6124'  | '1'                 | '463ac35c9f6413ad48485a3953bb6124' | 1                  ",
    "uint64 max traceId     | 'ffffffffffffffff'                  | '1'                 | 'ffffffffffffffff'                 | 1                  ",
    "128-bit high-low max   | 'aaaaaaaaaaaaaaaaffffffffffffffff'  | '1'                 | 'aaaaaaaaaaaaaaaaffffffffffffffff' | 1                  ",
    "traceId too long high1 | '1ffffffffffffffffffffffffffffffff' | '1'                 |                                    |                    ",
    "traceId too long high0 | '0ffffffffffffffffffffffffffffffff' | '1'                 |                                    |                    ",
    "uint64 max spanId      | '1'                                 | 'ffffffffffffffff'  | '1'                                | -1                 ",
    "spanId too long        | '1'                                 | '1ffffffffffffffff' |                                    |                    "
  })
  void extract128BitIdTruncatesIdTo64Bit(
      String traceId, String spanId, String expectedTraceIdHex, Long expectedSpanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (expectedTraceIdHex != null) {
      assertInstanceOf(ExtractedContext.class, context);
      B3TraceId expectedTraceId = B3TraceId.fromHex(expectedTraceIdHex);
      assertEquals(expectedTraceId, context.getTraceId());
      long spanIdValue = expectedSpanId == null ? 0L : expectedSpanId;
      assertEquals(spanIdValue, context.getSpanId());
      assertEquals(expectedTraceId.getOriginal(), context.getTags().getString(B3_TRACE_ID));
      if (expectedSpanId == null) {
        assertNull(context.getTags().getString(B3_SPAN_ID));
      } else {
        assertEquals(DDSpanId.toHexString(expectedSpanId), context.getTags().getString(B3_SPAN_ID));
      }
    } else if (context != null) {
      assertInstanceOf(TagContext.class, context);
      assertFalse(context instanceof ExtractedContext);
    }
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    TagContext context =
        this.extractor.extract(singletonMap(SOME_HEADER, SOME_VALUE), stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, SOME_VALUE), context.getTags());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwarded = "for=" + FORWARDED_IP + ":" + FORWARDED_PORT;
    Map<String, String> tagOnlyCtx = singletonMap("Forwarded", forwarded);
    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("Forwarded", forwarded);

    TagContext context = extractor.extract(tagOnlyCtx, stringValuesMap());

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
    Map<String, String> tagOnlyCtx = new HashMap<>();
    tagOnlyCtx.put("X-Forwarded-For", FORWARDED_IP);
    tagOnlyCtx.put("X-Forwarded-Port", FORWARDED_PORT);
    Map<String, String> fullCtx = new HashMap<>();
    fullCtx.put(TRACE_ID_KEY.toUpperCase(), "1");
    fullCtx.put(SPAN_ID_KEY.toUpperCase(), "2");
    fullCtx.put("x-forwarded-for", FORWARDED_IP);
    fullCtx.put("x-forwarded-port", FORWARDED_PORT);

    TagContext context = extractor.extract(tagOnlyCtx, stringValuesMap());

    assertNotNull(context);
    assertEquals(FORWARDED_IP, context.getXForwardedFor());
    assertEquals(FORWARDED_PORT, context.getXForwardedPort());

    context = extractor.extract(fullCtx, stringValuesMap());

    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(FORWARDED_IP, context.getXForwardedFor());
    assertEquals(FORWARDED_PORT, context.getXForwardedPort());
  }

  @Test
  void extractEmptyHeadersReturnsNull() {
    assertNull(
        extractor.extract(singletonMap("ignored-header", "ignored-value"), stringValuesMap()));
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "traceId");
    headers.put(SPAN_ID_KEY.toUpperCase(), "spanId");
    headers.put(SOME_HEADER, SOME_VALUE);

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, SOME_VALUE), context.getTags());
  }

  @Test
  void extractHttpHeadersWithOutOfRangeSpanId() {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), "0");
    headers.put(SPAN_ID_KEY.toUpperCase(), "-1");
    headers.put(SOME_HEADER, SOME_VALUE);

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, SOME_VALUE), context.getTags());
  }

  @TableTest({
    "scenario             | traceId                            | spanId                | expectedSpanId     ",
    "padded 64-bit        | '00001'                            | '00001'               | 1                  ",
    "normal 64-bit        | '463ac35c9f6413ad'                 | '463ac35c9f6413ad'    | 5060571933882717101",
    "128-bit truncated    | '463ac35c9f6413ad48485a3953bb6124' | '1'                   | 1                  ",
    "uint64 max traceId   | 'ffffffffffffffff'                 | '1'                   | 1                  ",
    "128-bit high+low max | 'aaaaaaaaaaaaaaaaffffffffffffffff' | '1'                   | 1                  ",
    "uint64 max spanId    | '1'                                | 'ffffffffffffffff'    | -1                 ",
    "padded uint64 max    | '1'                                | '000ffffffffffffffff' | -1                 "
  })
  void extractIdsWhileRetainingTheOriginalString(
      String traceId, String spanId, long expectedSpanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    B3TraceId expectedTraceId = B3TraceId.fromHex(traceId);

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(expectedTraceId, context.getTraceId());
    assertEquals(traceId, ((B3TraceId) context.getTraceId()).getOriginal());
    assertEquals(expectedSpanId, context.getSpanId());
    assertEquals(trimmed(spanId), DDSpanId.toHexString(context.getSpanId()));
  }

  private static String trimmed(String hex) {
    int length = hex.length();
    int firstNonZero = 0;
    while (firstNonZero < length && hex.charAt(firstNonZero) == '0') {
      firstNonZero++;
    }
    if (firstNonZero == length) {
      return "0";
    }
    return hex.substring(firstNonZero, length);
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

    TagContext context = extractor.extract(headers, stringValuesMap());

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
