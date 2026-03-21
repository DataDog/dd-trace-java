package datadog.trace.core.baggage;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_BYTES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_BAGGAGE_MAX_ITEMS;
import static datadog.trace.core.baggage.BaggagePropagator.BAGGAGE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BaggagePropagatorTest {

  static class MapCarrierAccessor
      implements CarrierSetter<Map<String, String>>, CarrierVisitor<Map<String, String>> {
    @Override
    public void set(Map<String, String> carrier, String key, String value) {
      if (carrier != null && key != null && value != null) {
        carrier.put(key, value);
      }
    }

    @Override
    public void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      carrier.forEach(visitor);
    }
  }

  BaggagePropagator propagator;
  CarrierSetter<Map<String, String>> setter;
  Map<String, String> carrier;
  Context context;

  @BeforeEach
  void setup() {
    this.propagator =
        new BaggagePropagator(
            true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    this.setter = new MapCarrierAccessor();
    this.carrier = new HashMap<>();
    this.context = Context.root();
  }

  static Stream<Arguments> testBaggagePropagatorContextInjectionArguments() {
    Map<String, String> map1 = new LinkedHashMap<>();
    map1.put("key1", "val1");
    map1.put("key2", "val2");
    map1.put("foo", "bar");

    Map<String, String> map2 = new LinkedHashMap<>();
    map2.put("\",;\\()/:<=>?@[]{}", "\",;\\");

    Map<String, String> map3 = new LinkedHashMap<>();
    map3.put("key1", "val1");

    Map<String, String> map4 = new LinkedHashMap<>();
    map4.put("key1", "val1");
    map4.put("key2", "val2");

    Map<String, String> map5 = new LinkedHashMap<>();
    map5.put("serverNode", "DF 28");

    Map<String, String> map6 = new LinkedHashMap<>();
    map6.put("userId", "Amélie");

    Map<String, String> map7 = new LinkedHashMap<>();
    map7.put("user!d(me)", "false");

    Map<String, String> map8 = new LinkedHashMap<>();
    map8.put("abcdefg", "hijklmnopq\u2665");

    return Stream.of(
        Arguments.of(map1, "key1=val1,key2=val2,foo=bar"),
        Arguments.of(map2, "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C"),
        Arguments.of(map3, "key1=val1"),
        Arguments.of(map4, "key1=val1,key2=val2"),
        Arguments.of(map5, "serverNode=DF%2028"),
        Arguments.of(map6, "userId=Am%C3%A9lie"),
        Arguments.of(map7, "user!d%28me%29=false"),
        Arguments.of(map8, "abcdefg=hijklmnopq%E2%99%A5"));
  }

  @ParameterizedTest(name = "[{index}] test baggage propagator context injection")
  @MethodSource("testBaggagePropagatorContextInjectionArguments")
  void testBaggagePropagatorContextInjection(Map<String, String> baggageMap, String baggageHeader) {
    this.context = Baggage.create(baggageMap).storeInto(this.context);

    this.propagator.inject(context, carrier, setter);

    assertEquals(baggageHeader, carrier.get(BAGGAGE_KEY));
  }

  static Stream<Arguments> testBaggageInjectItemLimitArguments() {
    Map<String, String> baggage1 = new LinkedHashMap<>();
    baggage1.put("key1", "val1");
    baggage1.put("key2", "val2");

    Map<String, String> baggage2 = new LinkedHashMap<>();
    baggage2.put("key1", "val1");
    baggage2.put("key2", "val2");
    baggage2.put("key3", "val3");

    return Stream.of(
        Arguments.of(baggage1, "key1=val1,key2=val2"),
        Arguments.of(baggage2, "key1=val1,key2=val2"));
  }

  @ParameterizedTest(name = "[{index}] test baggage inject item limit")
  @MethodSource("testBaggageInjectItemLimitArguments")
  void testBaggageInjectItemLimit(Map<String, String> baggage, String baggageHeader) {
    BaggagePropagator limitedPropagator =
        new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    context = Baggage.create(baggage).storeInto(context);

    limitedPropagator.inject(context, carrier, setter);

    assertEquals(baggageHeader, carrier.get(BAGGAGE_KEY));
  }

  static Stream<Arguments> testBaggageInjectBytesLimitArguments() {
    Map<String, String> baggage1 = new LinkedHashMap<>();
    baggage1.put("key1", "val1");
    baggage1.put("key2", "val2");

    Map<String, String> baggage2 = new LinkedHashMap<>();
    baggage2.put("key1", "val1");
    baggage2.put("key2", "val2");
    baggage2.put("key3", "val3");

    Map<String, String> baggage3 = new LinkedHashMap<>();
    baggage3.put("abcdefg", "hijklmnopq\u2665");

    return Stream.of(
        Arguments.of(baggage1, "key1=val1,key2=val2"),
        Arguments.of(baggage2, "key1=val1,key2=val2"),
        Arguments.of(baggage3, ""));
  }

  @ParameterizedTest(name = "[{index}] test baggage inject bytes limit")
  @MethodSource("testBaggageInjectBytesLimitArguments")
  void testBaggageInjectBytesLimit(Map<String, String> baggage, String baggageHeader) {
    BaggagePropagator limitedPropagator =
        new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20);
    context = Baggage.create(baggage).storeInto(context);

    limitedPropagator.inject(context, carrier, setter);

    assertEquals(baggageHeader, carrier.get(BAGGAGE_KEY));
  }

  static Stream<Arguments> testTracingPropagatorContextExtractorArguments() {
    Map<String, String> expected1 = new LinkedHashMap<>();
    expected1.put("key1", "val1");
    expected1.put("key2", "val2");
    expected1.put("foo", "bar");

    Map<String, String> expected2 = new LinkedHashMap<>();
    expected2.put("\",;\\()/:<=>?@[]{}", "\",;\\");

    return Stream.of(
        Arguments.of("key1=val1,key2=val2,foo=bar", expected1),
        Arguments.of(
            "%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C", expected2));
  }

  @ParameterizedTest(name = "[{index}] test tracing propagator context extractor")
  @MethodSource("testTracingPropagatorContextExtractorArguments")
  void testTracingPropagatorContextExtractor(String baggageHeader, Map<String, String> baggageMap) {
    Map<String, String> headers = new HashMap<>();
    headers.put(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    assertEquals(baggageMap, Baggage.fromContext(context).asMap());
  }

  @Test
  void testExtractingNonASCIIHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put(BAGGAGE_KEY, "key1=vallée,clé2=value");

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());
    Baggage baggage = Baggage.fromContext(context);

    // non ASCII values data are still accessible as part of the API
    assertNotNull(baggage);
    assertEquals("vallée", baggage.asMap().get("key1"));
    assertEquals("value", baggage.asMap().get("clé2"));
    assertNull(baggage.getW3cHeader());

    this.propagator.inject(Context.root().with(baggage), carrier, setter);

    // baggage are URL encoded if not valid, even if not modified
    assertEquals("key1=vall%C3%A9e,cl%C3%A92=value", carrier.get(BAGGAGE_KEY));
  }

  static Stream<Arguments> extractInvalidBaggageHeadersArguments() {
    return Stream.of(
        Arguments.of("no-equal-sign,foo=gets-dropped-because-previous-pair-is-malformed"),
        Arguments.of("foo=gets-dropped-because-subsequent-pair-is-malformed,="),
        Arguments.of("=no-key"),
        Arguments.of("no-value="),
        Arguments.of(""),
        Arguments.of(",,"),
        Arguments.of("="));
  }

  @ParameterizedTest(name = "[{index}] extract invalid baggage headers")
  @MethodSource("extractInvalidBaggageHeadersArguments")
  void extractInvalidBaggageHeaders(String baggageHeader) {
    Map<String, String> headers = new HashMap<>();
    headers.put(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    assertNull(Baggage.fromContext(context));
  }

  static Stream<Arguments> testBaggageCacheArguments() {
    return Stream.of(
        Arguments.of("key1=val1,key2=val2,foo=bar", "key1=val1,key2=val2,foo=bar"),
        Arguments.of("\";\\()/:<=>?@[]{}=\";\\", null));
  }

  @ParameterizedTest(name = "[{index}] test baggage cache")
  @MethodSource("testBaggageCacheArguments")
  void testBaggageCache(String baggageHeader, String cachedString) {
    Map<String, String> headers = new HashMap<>();
    headers.put(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggageContext = Baggage.fromContext(context);
    assertEquals(cachedString, baggageContext.getW3cHeader());
  }

  static Stream<Arguments> testBaggageCacheItemsLimitArguments() {
    return Stream.of(
        Arguments.of("key1=val1,key2=val2", "key1=val1,key2=val2"),
        Arguments.of("key1=val1,key2=val2,key3=val3", "key1=val1,key2=val2"),
        Arguments.of("key1=val1,key2=val2,key3=val3,key4=val4", "key1=val1,key2=val2"));
  }

  @ParameterizedTest(name = "[{index}] test baggage cache items limit")
  @MethodSource("testBaggageCacheItemsLimitArguments")
  void testBaggageCacheItemsLimit(String baggageHeader, String cachedString) {
    BaggagePropagator limitedPropagator =
        new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    Map<String, String> headers = new HashMap<>();
    headers.put(BAGGAGE_KEY, baggageHeader);

    context = limitedPropagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggageContext = Baggage.fromContext(context);
    assertEquals(cachedString, String.valueOf(baggageContext.getW3cHeader()));
  }

  static Stream<Arguments> testBaggageCacheBytesLimitArguments() {
    return Stream.of(
        Arguments.of("key1=val1,key2=val2", "key1=val1,key2=val2"),
        Arguments.of("key1=val1,key2=val2,key3=val3", "key1=val1,key2=val2"));
  }

  @ParameterizedTest(name = "[{index}] test baggage cache bytes limit")
  @MethodSource("testBaggageCacheBytesLimitArguments")
  void testBaggageCacheBytesLimit(String baggageHeader, String cachedString) {
    BaggagePropagator limitedPropagator =
        new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20);
    Map<String, String> headers = new HashMap<>();
    headers.put(BAGGAGE_KEY, baggageHeader);

    context = limitedPropagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggageContext = Baggage.fromContext(context);
    assertEquals(cachedString, String.valueOf(baggageContext.getW3cHeader()));
  }
}
