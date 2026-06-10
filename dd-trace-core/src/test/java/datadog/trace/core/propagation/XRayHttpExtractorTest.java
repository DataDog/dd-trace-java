package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static datadog.trace.core.propagation.XRayHttpCodec.X_AMZN_TRACE_ID;
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
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

@WithConfig(key = TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
class XRayHttpExtractorTest extends DDJavaSpecification {

  private static final String SOME_HEADER = "SOME_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER = "SOME_CUSTOM_BAGGAGE_HEADER";
  private static final String SOME_CUSTOM_BAGGAGE_HEADER_2 = "SOME_CUSTOM_BAGGAGE_HEADER_2";

  private HttpCodec.Extractor extractor;
  private boolean origAppSecActive;

  @BeforeEach
  void setup() {
    Map<String, String> baggageMap = new HashMap<>();
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER, "some-baggage");
    baggageMap.put(SOME_CUSTOM_BAGGAGE_HEADER_2, "some-CaseSensitive-baggage");
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(singletonMap(SOME_HEADER, "some-tag"))
            .setBaggageMapping(baggageMap)
            .apply();
    this.extractor = XRayHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void teardown() {
    APPSEC_ACTIVE = this.origAppSecActive;
  }

  @TableTest({
    "scenario    | traceId          | spanId           | samplingPriority | expectedSamplingPriority",
    "no sampling | 1                | 2                | ''               | UNSET                   ",
    "sampled 1   | 2                | 3                | ';Sampled=1'     | SAMPLER_KEEP            ",
    "sampled 0   | 3                | 4                | ';Sampled=0'     | SAMPLER_DROP            ",
    "max trace   | ffffffffffffffff | fffffffffffffffe | ';Sampled=0'     | SAMPLER_DROP            ",
    "max span    | fffffffffffffffe | ffffffffffffffff | ';Sampled=1'     | SAMPLER_KEEP            "
  })
  void extractHttpHeaders(
      String traceId,
      String spanId,
      String samplingPriority,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    // spotless:off
    Map<String, String> headers = headers(
        X_AMZN_TRACE_ID, "Root=1-00000000-00000000"
            + XRayTestHelper.zeroPadId(traceId)
            + ";Parent="
            + XRayTestHelper.zeroPadId(spanId)
            + samplingPriority
            + ";=empty key;empty value=;=;;",
        SOME_HEADER, "my-interesting-info",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2,"my-interesting-baggage-info-2"
    );
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.fromHex(traceId), context.getTraceId());
    assertEquals(DDSpanId.fromHex(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("empty value", "");
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    Map<String, String> headers = headers(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHeadersWithForwarding() {
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";
    String forwarded = "for=" + forwardedIp + ":" + forwardedPort;
    Map<String, String> tagOnlyCtx = headers("Forwarded", forwarded);
    // spotless:off
    Map<String, String> fullCtx = headers(
        X_AMZN_TRACE_ID, "Root=1-00000000-000000000000000000000001;Parent=0000000000000002",
        "Forwarded", forwarded
    );
    // spotless:on

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
    // spotless:off
    Map<String, String> tagOnlyCtx = headers(
        "X-Forwarded-For", forwardedIp,
        "X-Forwarded-Port", forwardedPort
    );
    Map<String, String> fullCtx = headers(
        "x-amzn-trace-id", "Root=1-00000000-000000000000000000000001;Parent=0000000000000002",
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

    assertInstanceOf(ExtractedContext.class, context);
    assertEquals(1L, context.getTraceId().toLong());
    assertEquals(2L, context.getSpanId());
    assertEquals(forwardedIp, context.getXForwardedFor());
    assertEquals(forwardedPort, context.getXForwardedPort());
  }

  @Test
  void noContextWithEmptyHeaders() {
    assertNull(
        this.extractor.extract(headers("ignored-header", "ignored-value"), stringValuesMap()));
  }

  @Test
  void noContextWithInvalidNonNumericId() {
    // spotless:off
    Map<String, String> headers = headers(
        "x-amzn-trace-Id", "Root=1-00000000-00000000000000000traceId;Parent=0000000000spanId",
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @Test
  void noContextWithTooLargeTraceId() {
    Map<String, String> headers =
        headers(
            X_AMZN_TRACE_ID, "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8");

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithNonZeroEpoch() {
    Map<String, String> headers =
        headers(
            X_AMZN_TRACE_ID, "Root=1-5759e988-00000000e1be46a994272793;Parent=53995c3f42cd8ad8");

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.fromHex("e1be46a994272793"), context.getTraceId());
    assertEquals(DDSpanId.fromHex("53995c3f42cd8ad8"), context.getSpanId());
    assertNull(context.getOrigin());
  }

  @TableTest({
    "scenario   | traceId          | spanId           | expectedTraceIdHex | expectedSpanId     ",
    "short ids  | 00001            | 00001            | 0000000000000001   | 1                  ",
    "long ids   | 463ac35c9f6413ad | 463ac35c9f6413ad | 463ac35c9f6413ad   | 5060571933882717101",
    "long trace | 48485a3953bb6124 | 1                | 48485a3953bb6124   | 1                  ",
    "max trace  | ffffffffffffffff | 1                | ffffffffffffffff   | 1                  ",
    "max span   | 1                | ffffffffffffffff | 0000000000000001   | -1                 "
  })
  void extractIdsWhileRetainingTheOriginalString(
      String traceId, String spanId, String expectedTraceIdHex, long expectedSpanId) {
    Map<String, String> headers =
        headers(
            X_AMZN_TRACE_ID,
            "Root=1-00000000-00000000"
                + XRayTestHelper.zeroPadId(traceId)
                + ";Parent="
                + XRayTestHelper.zeroPadId(spanId));

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.fromHex(expectedTraceIdHex), context.getTraceId());
    assertEquals(XRayTestHelper.zeroPadId(traceId), context.getTraceId().toHexStringPadded(16));
    assertEquals(expectedSpanId, context.getSpanId());
    assertEquals(XRayTestHelper.zeroPadId(spanId), DDSpanId.toHexStringPadded(context.getSpanId()));
  }

  @TableTest({
    "scenario | traceId | spanId | endToEndStartTime",
    "zero     | 1       | 2      | 0                ",
    "non-zero | 2       | 3      | 1610001234       "
  })
  void extractHeadersWithEndToEnd(String traceId, String spanId, long endToEndStartTime) {
    Map<String, String> headers =
        headers(
            X_AMZN_TRACE_ID,
            "Root=1-00000000-00000000"
                + XRayTestHelper.zeroPadId(traceId)
                + ";Parent="
                + XRayTestHelper.zeroPadId(spanId)
                + ";k1=v1;t0="
                + endToEndStartTime
                + ";k2=v2");

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(endToEndStartTime * 1_000_000L, context.getEndToEndStartTime());
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
        HttpCodec.CF_CONNECTING_IP_V6_KEY, "9.9.9.9"
    );
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
