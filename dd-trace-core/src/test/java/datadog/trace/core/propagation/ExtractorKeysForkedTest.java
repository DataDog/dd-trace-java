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
import datadog.context.propagation.HeaderSchema;
import datadog.context.propagation.IndexedCarrier;
import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
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

  @Test
  void mergingFromHeaderConstantsMatchesInterpretingEveryKey() {
    assumeFalse(ContextInterpreter.LOG_EXTRACT_HEADER_NAMES);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig = DynamicConfig.create().apply();
    List<HttpCodec.Extractor> parts = new ArrayList<>();
    parts.add(DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    parts.add(W3CHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    HttpCodec.CompoundExtractor compound = new HttpCodec.CompoundExtractor(parts, false);

    // every traceparent against every tracestate, Datadog headers matching, mismatching or absent
    List<Map<String, String>> corpus = CompoundExtractionGoldenTest.corpus(true);
    int lowerPriorityMalformed = 0;
    for (Map<String, String> headers : corpus) {
      // a String carrier runs the trace context interpreter over every key
      String interpreted = describe(compound.extract(headers, stringValuesMap()));
      // the composite propagator's cache knows each key's constant, so the merge reads the trace
      // context headers straight from it
      TagContext merged = compound.extract(new Indexed(headers, compound.keys()), WITH_HEADERS);
      String tracestate = headers.get("tracestate");
      boolean malformedDatadogElement =
          tracestate != null
              && (tracestate.contains("t.tid:zzz") || tracestate.contains("t.dm:bad"));
      if (interpreted.equals(describe(merged))) {
        continue;
      }
      // The only difference: the Datadog context wins and the W3C one is merged into it, and a
      // malformed element of the lower-priority tracestate's dd member, which the merge doesn't
      // use (the Datadog context has its own tags), no longer discards that tracestate. It is
      // carried for its other members, and its well-formed last parent id is the reparented span's
      // last parent (the interpreter dropped the tracestate, or its dd member, and fell back to
      // the Datadog parent id).
      assertTrue(
          malformedDatadogElement
              && merged instanceof ExtractedContext
              && "123456789".equals(headers.get("x-datadog-trace-id")),
          "unexpected difference for " + headers + "\n  interpreted: " + interpreted);
      assertEquals(
          tracestate,
          ((ExtractedContext) merged).getPropagationTags().getW3CTracestate(),
          headers.toString());
      if ("7".equals(headers.get("x-datadog-parent-id"))) {
        assertEquals(
            "00f067aa0ba902b7", merged.getTags().getString("_dd.parent_id"), headers.toString());
      }
      lowerPriorityMalformed++;
    }
    assertTrue(lowerPriorityMalformed > 0, "the corpus covers a malformed lower-priority element");
  }

  /** A carrier that knows each key's header constant, as the composite propagator's cache does. */
  static final class Indexed implements IndexedCarrier {
    final List<String> keys = new ArrayList<>();
    final List<String> values = new ArrayList<>();
    final List<HeaderSchema> headers = new ArrayList<>();
    final List<StringBuilder> views = new ArrayList<>();

    Indexed(Map<String, String> carrier, CarrierKeys classifier) {
      carrier.forEach((key, value) -> {
        HeaderSchema header = classifier.header(key);
        keys.add(key);
        values.add(value);
        headers.add(header);
        views.add(
            header != null && header.isParseOnly() && value != null
                ? new StringBuilder(value)
                : null);
      });
    }

    /** Overwrites every view, as a reused fetch buffer would. */
    void overwriteViews() {
      for (StringBuilder view : views) {
        if (view != null) {
          view.setLength(0);
          view.append("999");
        }
      }
    }

    @Override
    public int size() {
      return keys.size();
    }

    @Override
    public String key(int index) {
      return keys.get(index);
    }

    @Override
    public String value(int index) {
      return values.get(index);
    }

    @Override
    public HeaderSchema header(int index) {
      return headers.get(index);
    }

    /** Parse-only values as views that aren't {@code String}s, as a byte carrier hands them. */
    @Override
    public CharSequence valueView(int index) {
      StringBuilder view = views.get(index);
      return view != null ? view : values.get(index);
    }
  }

  /** Visits an {@link Indexed} carrier, handing each key's constant to the classifier. */
  static final AgentPropagation.ContextVisitor<Indexed> WITH_HEADERS =
      (carrier, classifier) -> {
        for (int i = 0; i < carrier.size(); i++) {
          if (!HttpCodec.acceptIndexed(classifier, carrier, i)) {
            return;
          }
        }
      };

  /** Every known header, so the constant path also sees headers a style doesn't read. */
  private static CarrierKeys everyKnownHeader() {
    CarrierKeys.Builder builder = CarrierKeys.builder();
    for (HeaderSchema header : HeaderSchema.values()) {
      builder.header(header);
    }
    return builder.build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("styles")
  void dispatchingOnHeaderConstantsExtractsTheSameContext(Style style) {
    HttpCodec.Extractor extractor = extractor(style);
    Map<String, String> headers = headers(style);
    // the style's own keys, and every known header
    for (CarrierKeys classifier : new CarrierKeys[] {extractor.keys(), everyKnownHeader()}) {
      assertEquals(
          describe(extractor.extract(headers, stringValuesMap())),
          describe(extractor.extract(new Indexed(headers, classifier), WITH_HEADERS)),
          classifier.toString());
    }
  }

  @Test
  void compoundExtractionReadsHeaderConstantsInPlace() {
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.singletonMap("x-custom-header", "tag.custom"))
            .apply();
    List<HttpCodec.Extractor> parts = new ArrayList<>();
    parts.add(DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    parts.add(W3CHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    HttpCodec.CompoundExtractor compound = new HttpCodec.CompoundExtractor(parts, false);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-trace-id", "123");
    headers.put("x-datadog-parent-id", "456");
    headers.put("x-datadog-sampling-priority", "1");
    headers.put("x-datadog-tags", "_dd.p.dm=-4");
    headers.put("traceparent", "00-0000000000000000000000000000007b-00000000000001c9-01");
    headers.put("tracestate", "dd=s:1;p:00000000000001c9");
    headers.put("X-Custom-Header", "custom");
    headers.put("content-type", "application/json");

    assertEquals(
        describe(compound.extract(headers, stringValuesMap())),
        describe(compound.extract(new Indexed(headers, compound.keys()), WITH_HEADERS)));
  }

  static String[][] datadogValues() {
    return new String[][] {
      {"123", "456", "1"},
      {"00123", "0456", "+1"},
      {"+123", "+456", "-1"},
      {"0", "0", "0"},
      {"123,456", "456, 789", "2,1"},
      {"abc", "456", "1"},
      {"123", "4x6", "1"},
      {"123", "456", "one"},
      {"18446744073709551615", "18446744073709551615", "2147483647"},
      {"18446744073709551616", "456", "2147483648"},
      {"9223372036854775807", "9223372036854775808", "-2147483648"},
    };
  }

  private static Map<String, String> datadogHeaders(
      String traceId, String parentId, String priority) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-trace-id", traceId);
    headers.put("x-datadog-parent-id", parentId);
    headers.put("x-datadog-sampling-priority", priority);
    headers.put("x-datadog-origin", "synthetics");
    headers.put("traceparent", "00-0000000000000000000000000000007b-00000000000001c8-01");
    return headers;
  }

  private static HttpCodec.Extractor datadogAndW3C() {
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig = DynamicConfig.create().apply();
    List<HttpCodec.Extractor> parts = new ArrayList<>();
    parts.add(DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    parts.add(W3CHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig));
    return new HttpCodec.CompoundExtractor(parts, false);
  }

  @ParameterizedTest
  @MethodSource("datadogValues")
  void parsingIdsFromViewsMatchesParsingStrings(String traceId, String parentId, String priority) {
    Map<String, String> headers = datadogHeaders(traceId, parentId, priority);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig = DynamicConfig.create().apply();
    HttpCodec.Extractor datadog =
        DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);
    for (HttpCodec.Extractor extractor : new HttpCodec.Extractor[] {datadog, datadogAndW3C()}) {
      assertEquals(
          describe(extractor.extract(headers, stringValuesMap())),
          describe(extractor.extract(new Indexed(headers, extractor.keys()), WITH_HEADERS)));
    }
  }

  @ParameterizedTest
  @MethodSource("datadogValues")
  void viewsAreNotKeptPastExtraction(String traceId, String parentId, String priority) {
    HttpCodec.Extractor extractor = datadogAndW3C();
    Indexed carrier = new Indexed(datadogHeaders(traceId, parentId, priority), extractor.keys());
    TagContext context = extractor.extract(carrier, WITH_HEADERS);
    String before = describe(context);
    carrier.overwriteViews();
    assertEquals(before, describe(context), "the context kept a view");
  }
}
