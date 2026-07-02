package datadog.trace.core.baggage;

import static datadog.trace.core.baggage.BaggagePropagator.BAGGAGE_KEY;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class BaggagePropagatorTest extends DDJavaSpecification {
  private static final int DEFAULT_TRACE_BAGGAGE_MAX_ITEMS = 64;
  private static final int DEFAULT_TRACE_BAGGAGE_MAX_BYTES = 8192;

  private BaggagePropagator propagator;
  private CarrierSetter<Map<String, String>> setter;
  private Map<String, String> carrier;
  private Context context;

  @ParametersAreNonnullByDefault
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

  @BeforeEach
  void setup() {
    this.propagator =
        new BaggagePropagator(
            true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    this.setter = new MapCarrierAccessor();
    this.carrier = new HashMap<>();
    this.context = Context.root();
  }

  @TableTest({
    "scenario                      | baggageMap                         | baggageHeader                                                     ",
    "three entries                 | [key1: val1, key2: val2, foo: bar] | 'key1=val1,key2=val2,foo=bar'                                     ",
    "special chars are URL encoded | ['\",;\\()/:<=>?@[]{}': '\",;\\']  | '%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C'",
    "single entry                  | [key1: val1]                       | 'key1=val1'                                                       ",
    "two entries                   | [key1: val1, key2: val2]           | 'key1=val1,key2=val2'                                             ",
    "space is encoded              | [serverNode: 'DF 28']              | 'serverNode=DF%2028'                                              ",
    "non ASCII value               | [userId: Amélie]                   | 'userId=Am%C3%A9lie'                                              ",
    "parenthesis in key            | ['user!d(me)': false]              | 'user!d%28me%29=false'                                            ",
    "non ASCII heart symbol        | [abcdefg: 'hijklmnopq♥']          | 'abcdefg=hijklmnopq%E2%99%A5'                                      "
  })
  void testBaggagePropagatorContextInjection(Map<String, String> baggageMap, String baggageHeader) {
    this.context = Baggage.create(baggageMap).storeInto(this.context);

    this.propagator.inject(context, carrier, setter);

    assertEquals(baggageHeader, carrier.get(BAGGAGE_KEY));
  }

  @TableTest({
    "scenario            | baggage                              | baggageHeader        ",
    "limit not reached   | [key1: val1, key2: val2]             | 'key1=val1,key2=val2'",
    "third entry dropped | [key1: val1, key2: val2, key3: val3] | 'key1=val1,key2=val2'"
  })
  void testBaggageInjectItemLimit(Map<String, String> baggage, String baggageHeader) {
    // Creating propagator with test item limit
    propagator = new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    context = Baggage.create(baggage).storeInto(context);

    this.propagator.inject(context, carrier, setter);

    assertEquals(baggageHeader, carrier.get(BAGGAGE_KEY));
  }

  @TableTest({
    "scenario                                | baggage                              | baggageHeader        ",
    "limit not reached                       | [key1: val1, key2: val2]             | 'key1=val1,key2=val2'",
    "third entry exceeds bytes               | [key1: val1, key2: val2, key3: val3] | 'key1=val1,key2=val2'",
    "single entry exceeds bytes once encoded | [abcdefg: 'hijklmnopq♥']            | ''                    "
  })
  void testBaggageInjectBytesLimit(Map<String, String> baggage, String baggageHeader) {
    // Creating propagator with test bytes limit
    propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20);
    context = Baggage.create(baggage).storeInto(context);

    this.propagator.inject(context, carrier, setter);

    assertEquals(baggageHeader, carrier.get(BAGGAGE_KEY));
  }

  @TableTest({
    "scenario                  | baggageHeader                                                      | baggageMap                        ",
    "three entries             | 'key1=val1,key2=val2,foo=bar'                                      | [key1: val1, key2: val2, foo: bar]",
    "URL encoded special chars | '%22%2C%3B%5C%28%29%2F%3A%3C%3D%3E%3F%40%5B%5D%7B%7D=%22%2C%3B%5C' | ['\",;\\()/:<=>?@[]{}': '\",;\\'] "
  })
  void testTracingPropagatorContextExtractor(String baggageHeader, Map<String, String> baggageMap) {
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggage = Baggage.fromContext(context);
    assertNotNull(baggage);
    assertEquals(baggageMap, baggage.asMap());
  }

  @Test
  void testExtractingNonAsciiHeaders() {
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, "key1=vallée,clé2=value");

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());
    Baggage baggage = Baggage.fromContext(context);

    // non-ASCII values data are still accessible as part of the API
    assertNotNull(baggage);
    assertEquals("vallée", baggage.asMap().get("key1"));
    assertEquals("value", baggage.asMap().get("clé2"));
    assertNull(baggage.getW3cHeader());

    this.propagator.inject(Context.root().with(baggage), carrier, setter);

    // baggage are URL encoded if not valid, even if not modified
    assertEquals("key1=vall%C3%A9e,cl%C3%A92=value", carrier.get(BAGGAGE_KEY));
  }

  @TableTest({
    "scenario                    | baggageHeader                                                      ",
    "no equal sign in first pair | 'no-equal-sign,foo=gets-dropped-because-previous-pair-is-malformed'",
    "trailing equals only        | 'foo=gets-dropped-because-subsequent-pair-is-malformed,='          ",
    "empty key                   | '=no-key'                                                          ",
    "empty value                 | 'no-value='                                                        ",
    "empty header                | ''                                                                 ",
    "only delimiters             | ',,'                                                               ",
    "single equal sign           | '='                                                                "
  })
  void extractInvalidBaggageHeaders(String baggageHeader) {
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    assertNull(Baggage.fromContext(context));
  }

  @TableTest({
    "scenario                  | baggageHeader                 | cachedString                 ",
    "valid header is cached    | 'key1=val1,key2=val2,foo=bar' | 'key1=val1,key2=val2,foo=bar'",
    "invalid chars => no cache | '\";\\()/:<=>?@[]{}=\";\\'    |                              "
  })
  void testBaggageCache(String baggageHeader, String cachedString) {
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggage = Baggage.fromContext(context);
    assertNotNull(baggage);
    assertEquals(cachedString, baggage.getW3cHeader());
  }

  @TableTest({
    "scenario               | baggageHeader                             | cachedString         ",
    "limit not reached      | 'key1=val1,key2=val2'                     | 'key1=val1,key2=val2'",
    "third entry truncates  | 'key1=val1,key2=val2,key3=val3'           | 'key1=val1,key2=val2'",
    "fourth entry truncates | 'key1=val1,key2=val2,key3=val3,key4=val4' | 'key1=val1,key2=val2'"
  })
  void testBaggageCacheItemsLimit(String baggageHeader, String cachedString) {
    // creating a new instance after injecting config
    propagator = new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggage = Baggage.fromContext(context);
    assertNotNull(baggage);
    assertEquals(cachedString, baggage.getW3cHeader());
  }

  @TableTest({
    "scenario              | baggageHeader                   | cachedString         ",
    "limit not reached     | 'key1=val1,key2=val2'           | 'key1=val1,key2=val2'",
    "third entry truncates | 'key1=val1,key2=val2,key3=val3' | 'key1=val1,key2=val2'"
  })
  void testBaggageCacheBytesLimit(String baggageHeader, String cachedString) {
    // Creating propagator with test bytes limit
    propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20);
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    Baggage baggage = Baggage.fromContext(context);
    assertNotNull(baggage);
    assertEquals(cachedString, baggage.getW3cHeader());
  }

  @TableTest({
    "scenario            | baggageHeader                   | baggageMap              ",
    "single entry        | 'key1=val1'                     | [key1: val1]            ",
    "two entries         | 'key1=val1,key2=val2'           | [key1: val1, key2: val2]",
    "third entry dropped | 'key1=val1,key2=val2,key3=val3' | [key1: val1, key2: val2]"
  })
  void testBaggageExtractItemsLimit(String baggageHeader, Map<String, String> baggageMap) {
    // Creating propagator with test item limit
    propagator = new BaggagePropagator(true, true, 2, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    // parsing stops once the item limit is exceeded
    Baggage baggage = Baggage.fromContext(context);
    assertNotNull(baggage);
    assertEquals(baggageMap, baggage.asMap());
  }

  @TableTest({
    "scenario            | baggageHeader                   | baggageMap              ",
    "single entry        | 'key1=val1'                     | [key1: val1]            ",
    "two entries         | 'key1=val1,key2=val2'           | [key1: val1, key2: val2]",
    "third entry dropped | 'key1=val1,key2=val2,key3=val3' | [key1: val1, key2: val2]"
  })
  void testBaggageExtractBytesLimit(String baggageHeader, Map<String, String> baggageMap) {
    // Creating propagator with test bytes limit
    propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 20);
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, baggageHeader);

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    // parsing stops once the byte limit is exceeded
    Baggage baggage = Baggage.fromContext(context);
    assertNotNull(baggage);
    assertEquals(baggageMap, baggage.asMap());
  }

  @Test
  void testBaggageExtract0ItemLimit() {
    // creating a new instance after injecting config
    propagator = new BaggagePropagator(true, true, 0, DEFAULT_TRACE_BAGGAGE_MAX_BYTES);
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, "key1=value1");

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    assertNull(Baggage.fromContext(context));
  }

  @Test
  void testBaggageExtract0ByteLimit() {
    // creating a new instance after injecting config
    propagator = new BaggagePropagator(true, true, DEFAULT_TRACE_BAGGAGE_MAX_ITEMS, 0);
    Map<String, String> headers = singletonMap(BAGGAGE_KEY, "key1=value1");

    context = this.propagator.extract(context, headers, ContextVisitors.stringValuesMap());

    assertNull(Baggage.fromContext(context));
  }
}
