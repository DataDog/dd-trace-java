package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Verifies that a {@code DD_TRACE_REQUEST_HEADER_TAGS} mapping onto a header that the {@link
 * ContextInterpreter} also consumes for another purpose (user-agent, forwarding, client-IP) is
 * still honoured. This is the general form of the APMS-20654 bug, which was fixed for {@code
 * X-Amzn-Trace-Id} in PR #12662: the {@code handled*} helpers short-circuit {@code accept()} with
 * an early {@code return true} before {@code handleTags} runs, so the mapped tag is silently
 * dropped.
 *
 * <p>The behaviour lives in {@link ContextInterpreter} and is duplicated across every propagation
 * style, so each style is exercised. AppSec is enabled so that {@code collectIpHeaders} is true and
 * the forwarding / client-IP helpers actually consume their headers.
 */
@WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
class ConsumedHeaderTagMappingTest extends DDJavaSpecification {
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
                "B3Multi",
                B3HttpCodec::newMultiExtractor,
                headers(B3HttpCodec.TRACE_ID_KEY, "1", B3HttpCodec.SPAN_ID_KEY, "2"),
                true)),
        arguments(
            new Style(
                "B3Single",
                B3HttpCodec::newSingleExtractor,
                headers(B3HttpCodec.B3_KEY, "1-2-1"),
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
        arguments(new Style("None", NoneCodec::newExtractor, emptyMap(), false)));
  }

  /**
   * Every header consumed by the {@code handled*} helpers, mapped to a distinct value. The tag name
   * for each is {@code "tag." + headerKey}.
   */
  private static Map<String, String> consumedHeaderValues() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put(HttpCodec.USER_AGENT_KEY, "test-agent"); // handledUserAgent
    values.put(HttpCodec.X_FORWARDED_PROTO_KEY, "https"); // handledXForwarding
    values.put(HttpCodec.X_FORWARDED_HOST_KEY, "example.com");
    values.put(HttpCodec.X_FORWARDED_FOR_KEY, "1.1.1.1");
    values.put(HttpCodec.X_FORWARDED_PORT_KEY, "8443");
    values.put(HttpCodec.FORWARDED_KEY, "for=2.2.2.2"); // handledForwarding
    values.put(HttpCodec.FORWARDED_FOR_KEY, "3.3.3.3");
    values.put(HttpCodec.X_CLUSTER_CLIENT_IP_KEY, "4.4.4.4"); // handledIpHeaders
    values.put(HttpCodec.X_REAL_IP_KEY, "5.5.5.5");
    values.put(HttpCodec.X_CLIENT_IP_KEY, "6.6.6.6");
    values.put(HttpCodec.TRUE_CLIENT_IP_KEY, "7.7.7.7");
    values.put(HttpCodec.FASTLY_CLIENT_IP_KEY, "8.8.8.8");
    values.put(HttpCodec.CF_CONNECTING_IP_KEY, "9.9.9.9");
    values.put(HttpCodec.CF_CONNECTING_IP_V6_KEY, "2001:db8::1");
    return values;
  }

  private static String tagFor(String headerKey) {
    return "tag." + headerKey;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void honoursHeaderTagsMappedOntoConsumedHeaders(Style style) {
    Map<String, String> consumed = consumedHeaderValues();

    Map<String, String> headerTags = new HashMap<>();
    consumed.keySet().forEach(header -> headerTags.put(header, tagFor(header)));
    this.extractor = buildExtractorWithHeaderTags(style.factory, headerTags);

    Map<String, String> requestHeaders = new HashMap<>(style.minimalTraceHeaders);
    requestHeaders.putAll(consumed);

    TagContext context = this.extractor.extract(requestHeaders, stringValuesMap());

    assertNotNull(context);
    // The header tag mapped onto each consumed header must be honoured (APMS-20654).
    consumed.forEach(
        (header, value) ->
            assertEquals(
                value,
                context.getTags().getString(tagFor(header)),
                "header tag mapped onto '" + header + "' should be honoured"));

    // The semantic capture of each header must be preserved.
    assertEquals("test-agent", context.getUserAgent());
    assertEquals("https", context.getXForwardedProto());
    assertEquals("example.com", context.getXForwardedHost());
    assertEquals("1.1.1.1", context.getXForwardedFor());
    assertEquals("8443", context.getXForwardedPort());
    assertEquals("for=2.2.2.2", context.getForwarded());
    assertEquals("3.3.3.3", context.getForwardedFor());
    assertEquals("4.4.4.4", context.getXClusterClientIp());
    assertEquals("5.5.5.5", context.getXRealIp());
    assertEquals("6.6.6.6", context.getXClientIp());
    assertEquals("7.7.7.7", context.getTrueClientIp());
    assertEquals("8.8.8.8", context.getFastlyClientIp());
    assertEquals("9.9.9.9", context.getCfConnectingIp());
    assertEquals("2001:db8::1", context.getCfConnectingIpv6());

    // The trace context must still be extracted (styles that carry one).
    if (style.buildsExtractedContext) {
      ExtractedContext extracted = assertInstanceOf(ExtractedContext.class, context);
      assertEquals(1L, extracted.getTraceId().toLong());
      assertEquals(2L, extracted.getSpanId());
    } else {
      assertFalse(context instanceof ExtractedContext);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  @WithConfig(key = TRACE_CLIENT_IP_HEADER, value = "my-header")
  void honoursHeaderTagMappedOntoCustomIpHeader(Style style) {
    // The extractor must be built after @WithConfig is applied: ContextInterpreter reads the
    // custom client-IP header name in its constructor.
    this.extractor =
        buildExtractorWithHeaderTags(
            style.factory, singletonHeaderTag("my-header", "tag.my-header"));

    Map<String, String> requestHeaders = new HashMap<>(style.minimalTraceHeaders);
    requestHeaders.put("my-header", "8.8.8.8");

    TagContext context = this.extractor.extract(requestHeaders, stringValuesMap());

    assertNotNull(context);
    assertEquals("8.8.8.8", context.getTags().getString("tag.my-header"));
    assertEquals("8.8.8.8", context.getCustomIpHeader());
  }

  private static Map<String, String> singletonHeaderTag(String header, String tag) {
    Map<String, String> mapping = new HashMap<>();
    mapping.put(header, tag);
    return mapping;
  }

  static HttpCodec.Extractor buildExtractorWithHeaderTags(
      BiFunction<Config, Supplier<TraceConfig>, HttpCodec.Extractor> factory,
      Map<String, String> headerTags) {
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(headerTags).apply();
    return factory.apply(Config.get(), dynamicConfig::captureTraceConfig);
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
