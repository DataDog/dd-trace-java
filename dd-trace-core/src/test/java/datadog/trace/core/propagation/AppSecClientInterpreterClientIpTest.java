package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.AbstractHttpExtractorTest.buildExtractor;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies client-IP AppSec HTTP header collection. This behavior is implemented in the {@link
 * ContextInterpreter} and should work with every propagation style.
 */
@WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
class AppSecClientInterpreterClientIpTest extends DDJavaSpecification {
  private boolean origAppSecActive;
  private HttpCodec.Extractor extractor;

  @BeforeEach
  void enableAppSec() {
    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSec() {
    if (this.extractor != null) {
      this.extractor.cleanup();
    }
    APPSEC_ACTIVE = this.origAppSecActive;
  }

  static Stream<Arguments> styles() {
    return Stream.of(
        arguments(
            new Style(
                "Datadog",
                DatadogHttpCodec::newExtractor,
                headers(DatadogHttpCodec.TRACE_ID_KEY, "1", DatadogHttpCodec.SPAN_ID_KEY, "2"),
                true)),
        arguments(
            new Style(
                "B3",
                B3HttpCodec::newExtractor,
                headers(B3HttpCodec.TRACE_ID_KEY, "1", B3HttpCodec.SPAN_ID_KEY, "2"),
                true)),
        arguments(
            new Style(
                "W3C",
                W3CHttpCodec::newExtractor,
                headers(
                    W3CHttpCodec.TRACE_PARENT_KEY,
                    "00-00000000000000000000000000000001-0000000000000002-01"),
                true)),
        arguments(
            new Style(
                "Haystack",
                HaystackHttpCodec::newExtractor,
                headers(HaystackHttpCodec.TRACE_ID_KEY, "1", HaystackHttpCodec.SPAN_ID_KEY, "2"),
                true)),
        arguments(
            new Style(
                "XRay",
                XRayHttpCodec::newExtractor,
                headers(
                    XRayHttpCodec.X_AMZN_TRACE_ID,
                    "Root=1-00000000-000000000000000000000001;Parent=0000000000000002"),
                true)),
        arguments(
            new Style(
                "None",
                NoneCodec::newExtractor,
                headers(DatadogHttpCodec.TRACE_ID_KEY, "1", DatadogHttpCodec.SPAN_ID_KEY, "2"),
                false)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void extractCommonHttpHeaders(Style style) {
    this.extractor = buildExtractor(style.factory);
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void extractEmptyHeadersReturnsNull(Style style) {
    this.extractor = buildExtractor(style.factory);
    assertNull(
        this.extractor.extract(headers("ignored-header", "ignored-value"), stringValuesMap()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void extractHeadersWithForwarding(Style style) {
    this.extractor = buildExtractor(style.factory);
    String forwarded = "for=1.2.3.4:1234";

    TagContext tagOnly = this.extractor.extract(headers("Forwarded", forwarded), stringValuesMap());

    assertNotNull(tagOnly);
    assertFalse(tagOnly instanceof ExtractedContext);
    assertEquals(forwarded, tagOnly.getForwarded());

    Map<String, String> fullHeaders = new HashMap<>(style.minimalTraceHeaders);
    fullHeaders.putAll(headers("Forwarded", forwarded));

    TagContext full = this.extractor.extract(fullHeaders, stringValuesMap());

    assertExpectedTraceContext(full, style);
    assertEquals(forwarded, full.getForwarded());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void extractHeadersWithXForwarding(Style style) {
    this.extractor = buildExtractor(style.factory);
    String forwardedIp = "1.2.3.4";
    String forwardedPort = "1234";

    TagContext tagOnly =
        this.extractor.extract(
            headers("X-Forwarded-For", forwardedIp, "X-Forwarded-Port", forwardedPort),
            stringValuesMap());

    assertNotNull(tagOnly);
    assertFalse(tagOnly instanceof ExtractedContext);
    assertEquals(forwardedIp, tagOnly.getXForwardedFor());
    assertEquals(forwardedPort, tagOnly.getXForwardedPort());

    Map<String, String> fullHeaders = new HashMap<>(style.minimalTraceHeaders);
    fullHeaders.putAll(headers("X-Forwarded-For", forwardedIp, "X-Forwarded-Port", forwardedPort));

    TagContext full = this.extractor.extract(fullHeaders, stringValuesMap());

    assertExpectedTraceContext(full, style);
    assertEquals(forwardedIp, full.getXForwardedFor());
    assertEquals(forwardedPort, full.getXForwardedPort());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  @WithConfig(key = TRACE_CLIENT_IP_RESOLVER_ENABLED, value = "false")
  void extractHeadersWithIpResolutionDisabled(Style style) {
    // The extractor must be built after @WithConfig is applied: ContextInterpreter reads the
    // client-IP resolution flag in its constructor.
    this.extractor = buildExtractor(style.factory);
    Map<String, String> headers = headers("X-Forwarded-For", "::1", "User-agent", "foo/bar");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNotNull(context);
    assertNull(context.getXForwardedFor());
    assertEquals("foo/bar", context.getUserAgent());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void extractHeadersWithIpResolutionDisabledAppsecDisabled(Style style) {
    this.extractor = buildExtractor(style.factory);
    // collectIpHeaders is recomputed per extract in ContextInterpreter.reset(), so toggling
    // APPSEC_ACTIVE after building the extractor still takes effect.
    APPSEC_ACTIVE = false;
    Map<String, String> headers = headers("X-Forwarded-For", "::1", "User-agent", "foo/bar");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNotNull(context);
    assertNull(context.getXForwardedFor());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  @WithConfig(key = TRACE_CLIENT_IP_HEADER, value = "my-header")
  void customIpHeaderCollectionDoesNotDisableStandardIpHeaderCollection(Style style) {
    this.extractor = buildExtractor(style.factory);
    Map<String, String> headers = headers("X-Forwarded-For", "::1", "My-Header", "8.8.8.8");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNotNull(context);
    assertEquals("::1", context.getXForwardedFor());
    assertEquals("8.8.8.8", context.getCustomIpHeader());
  }

  private static void assertExpectedTraceContext(TagContext context, Style style) {
    if (style.buildsExtractedContext) {
      ExtractedContext extracted = assertInstanceOf(ExtractedContext.class, context);
      assertEquals(1L, extracted.getTraceId().toLong());
      assertEquals(2L, extracted.getSpanId());
    } else {
      assertFalse(context instanceof ExtractedContext);
      assertEquals(DDTraceId.ZERO, context.getTraceId());
      assertEquals(DDSpanId.ZERO, context.getSpanId());
    }
  }

  /** A propagation style under test: its extractor factory and minimal valid trace headers. */
  static final class Style {
    final String name;
    final BiFunction<Config, Supplier<TraceConfig>, HttpCodec.Extractor> factory;
    final Map<String, String> minimalTraceHeaders;
    final boolean buildsExtractedContext;

    Style(
        String name,
        BiFunction<Config, Supplier<TraceConfig>, HttpCodec.Extractor> factory,
        Map<String, String> minimalTraceHeaders,
        boolean buildsExtractedContext) {
      this.name = name;
      this.factory = factory;
      this.minimalTraceHeaders = minimalTraceHeaders;
      this.buildsExtractedContext = buildsExtractedContext;
    }

    @Override
    public String toString() {
      return this.name;
    }
  }
}
