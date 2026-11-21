package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.ActiveSubsystems.APPSEC_ACTIVE;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.context.propagation.CarrierKeys;
import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.propagation.ConsumedHeaderTagMappingTest.Style;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An extractor's {@link HttpCodec.Extractor#keys()} covers every header it reads, so a carrier that
 * skips the other keys extracts the same context.
 *
 * <p>Forked: {@link ContextInterpreter#LOG_EXTRACT_HEADER_NAMES} is read once per JVM, and other
 * propagation tests turn it on, which makes every extractor keep all keys.
 */
class ExtractorKeysForkedTest extends DDJavaSpecification {

  private boolean origAppSecActive;

  @BeforeEach
  void enableAppSec() {
    // client IP headers are read only with AppSec (or AI Guard) active
    this.origAppSecActive = APPSEC_ACTIVE;
    APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSec() {
    APPSEC_ACTIVE = this.origAppSecActive;
  }

  static Iterable<?> styles() {
    List<Object> styles = new ArrayList<>();
    ConsumedHeaderTagMappingTest.styles().forEach(arguments -> styles.add(arguments.get()[0]));
    return styles;
  }

  /** Headers a style reads, a mapped header tag, baggage, and application headers nothing reads. */
  private static Map<String, String> headers(Style style) {
    Map<String, String> headers = new LinkedHashMap<>(style.minimalTraceHeaders);
    headers.putAll(ConsumedHeaderTagMappingTest.consumedHeaderValues());
    headers.put("X-Custom-Header", "custom");
    headers.put("ot-baggage-user", "u");
    headers.put("Baggage-account", "a");
    headers.put("content-type", "application/json");
    headers.put("x-app-request-id", "r-1");
    return headers;
  }

  private static HttpCodec.Extractor extractor(Style style) {
    return ConsumedHeaderTagMappingTest.buildExtractorWithHeaderTags(
        style.factory, Collections.singletonMap("x-custom-header", "tag.custom"));
  }

  private static String describe(TagContext context) {
    StringBuilder sb = new StringBuilder();
    CompoundExtractionGoldenTest.describeContext(sb, context);
    if (context != null) {
      sb.append(" baggage=").append(context.getBaggage());
      sb.append(" headers=").append(describeHeaders(context.getXForwardedFor(), context));
    }
    return sb.toString();
  }

  private static String describeHeaders(String xForwardedFor, TagContext context) {
    return "xff=" + xForwardedFor + " ua=" + context.getUserAgent() + " ip=" + context.getXRealIp();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void skippingKeysOutsideTheExtractorsKeysExtractsTheSameContext(Style style) {
    HttpCodec.Extractor extractor = extractor(style);
    Map<String, String> headers = headers(style);
    CarrierKeys keys = extractor.keys();

    Map<String, String> filtered = new LinkedHashMap<>();
    headers.forEach((key, value) -> {
      if (keys.contains(key)) {
        filtered.put(key, value);
      }
    });

    assertEquals(
        describe(extractor.extract(headers, stringValuesMap())),
        describe(extractor.extract(filtered, stringValuesMap())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void keysLeaveOutHeadersNothingReads(Style style) {
    assumeFalse(
        ContextInterpreter.LOG_EXTRACT_HEADER_NAMES,
        "header names are logged in this JVM, so every key is kept");
    CarrierKeys keys = extractor(style).keys();

    assertFalse(keys.isAll());
    assertFalse(keys.contains("content-type"));
    assertFalse(keys.contains("x-app-request-id"));
    for (String key : style.minimalTraceHeaders.keySet()) {
      assertTrue(keys.contains(key), key);
    }
    for (String key : ConsumedHeaderTagMappingTest.consumedHeaderValues().keySet()) {
      assertTrue(keys.contains(key), key);
    }
    assertTrue(keys.contains("X-Custom-Header"), "configured header tag");
  }

  @Test
  void everyStylesHeaderNamesAreInItsKeys() throws IllegalAccessException {
    assumeFalse(ContextInterpreter.LOG_EXTRACT_HEADER_NAMES);
    CarrierKeys keys = CarrierKeys.none();
    for (Object style : styles()) {
      keys = keys.union(extractor((Style) style).keys());
    }
    List<String> missing = new ArrayList<>();
    for (Class<?> codec :
        new Class<?>[] {
          HttpCodec.class,
          DatadogHttpCodec.class,
          W3CHttpCodec.class,
          B3HttpCodec.class,
          HaystackHttpCodec.class,
          XRayHttpCodec.class
        }) {
      for (Field field : codec.getDeclaredFields()) {
        if (isHeaderNameConstant(codec, field)) {
          field.setAccessible(true);
          String name = (String) field.get(null);
          if (!keys.contains(name)) {
            missing.add(codec.getSimpleName() + "." + field.getName() + " = " + name);
          }
        }
      }
    }
    assertEquals(Collections.emptyList(), missing, "header names missing from the keys");
  }

  /** A {@code static final String *_KEY} constant naming a header. */
  private static boolean isHeaderNameConstant(Class<?> codec, Field field) {
    int modifiers = field.getModifiers();
    if (!Modifier.isStatic(modifiers)
        || !Modifier.isFinal(modifiers)
        || field.getType() != String.class
        || !field.getName().endsWith("_KEY")) {
      return false;
    }
    // XRay's E2E_START_KEY is a field inside the X-Amzn-Trace-Id value, not a header
    if (codec == XRayHttpCodec.class && field.getName().equals("E2E_START_KEY")) {
      return false;
    }
    // *_BAGGAGE_KEY constants name baggage items a style writes, not headers it reads
    return !field.getName().endsWith("_BAGGAGE_KEY");
  }

  @Test
  void keysFollowTraceConfigChanges() {
    assumeFalse(ContextInterpreter.LOG_EXTRACT_HEADER_NAMES);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.singletonMap("x-first", "tag.first"))
            .apply();
    HttpCodec.Extractor extractor =
        W3CHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    CarrierKeys first = extractor.keys();
    assertSame(first, extractor.keys(), "rebuilt without a config change");
    assertTrue(first.contains("x-first"));

    dynamicConfig
        .initial()
        .setHeaderTags(Collections.singletonMap("x-second", "tag.second"))
        .setBaggageMapping(Collections.singletonMap("x-baggage-header", "item"))
        .apply();
    CarrierKeys second = extractor.keys();

    assertNotSame(first, second);
    assertTrue(second.contains("x-second"));
    assertTrue(second.contains("x-baggage-header"));
    assertFalse(second.contains("x-first"));
  }

  @Test
  void compoundExtractorKeysAreTheUnion() {
    assumeFalse(ContextInterpreter.LOG_EXTRACT_HEADER_NAMES);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig = DynamicConfig.create().apply();
    List<HttpCodec.Extractor> parts = new ArrayList<>();
    parts.add(DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    parts.add(W3CHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    HttpCodec.CompoundExtractor compound = new HttpCodec.CompoundExtractor(parts, false);

    CarrierKeys keys = compound.keys();
    assertTrue(keys.contains("x-datadog-trace-id"));
    assertTrue(keys.contains("traceparent"));
    assertSame(keys, compound.keys());
  }
}
