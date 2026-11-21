package datadog.context.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.propagation.CompositePropagator.CarrierCache;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarrierCacheTest {

  @Test
  void readsPairsByIndexInOrder() {
    CarrierCache cache = new CarrierCache();
    cache.accept("traceparent", "tp");
    cache.accept("baggage", "b");

    assertEquals(2, cache.size());
    assertEquals("traceparent", cache.key(0));
    assertEquals("tp", cache.value(0));
    assertEquals("baggage", cache.key(1));
    assertEquals("b", cache.value(1));
  }

  @Test
  void growsPastItsInitialCapacity() {
    CarrierCache cache = new CarrierCache();
    for (int i = 0; i < 40; i++) {
      cache.accept("key-" + i, "value-" + i);
    }

    assertEquals(40, cache.size());
    for (int i = 0; i < 40; i++) {
      assertEquals("key-" + i, cache.key(i));
      assertEquals("value-" + i, cache.value(i));
    }
  }

  @Test
  void visitsEveryPairInOrder() {
    CarrierCache cache = new CarrierCache();
    for (int i = 0; i < 20; i++) {
      cache.accept("key-" + i, "value-" + i);
    }
    List<String> seen = new ArrayList<>();
    cache.forEachKeyValue(cache, (key, value) -> seen.add(key + "=" + value));

    assertEquals(20, seen.size());
    assertEquals("key-0=value-0", seen.get(0));
    assertEquals("key-19=value-19", seen.get(19));
  }

  @Test
  void recordsEachKeysHeaderConstant() {
    CarrierCache cache =
        new CarrierCache(
            CarrierKeys.builder().header(HeaderSchema.TRACEPARENT).name("x-custom").build());
    cache.accept("traceparent", "tp");
    cache.accept("x-custom", "c");
    cache.accept(null, "no key");
    assertSame(HeaderSchema.TRACEPARENT, cache.header(0));
    assertNull(cache.header(1));
    assertNull(cache.header(2));
    assertNull(cache.key(2));
  }

  @Test
  void keepsHeaderConstantsPastItsInitialCapacity() {
    CarrierCache cache = new CarrierCache(CarrierKeys.of(HeaderSchema.BAGGAGE));
    for (int i = 0; i < 20; i++) {
      cache.accept(i % 2 == 0 ? "baggage" : "key-" + i, "value-" + i);
    }
    for (int i = 0; i < 20; i++) {
      if (i % 2 == 0) {
        assertSame(HeaderSchema.BAGGAGE, cache.header(i));
      } else {
        assertNull(cache.header(i));
      }
    }
  }

  @Test
  void keepsTransientValuesUntilReadAsStrings() {
    CarrierCache cache = new CarrierCache(CarrierKeys.of(HeaderSchema.TRACEPARENT));
    StringBuilder view = new StringBuilder("123");
    cache.accept("traceparent", "tp");
    cache.acceptTransient(HeaderSchema.X_DATADOG_TRACE_ID, "x-datadog-trace-id", view);

    assertSame(HeaderSchema.X_DATADOG_TRACE_ID, cache.header(1));
    assertSame(view, cache.valueView(1));
    assertEquals("tp", cache.valueView(0));
    // reading it as a String copies it once, and keeps the copy
    String copy = cache.value(1);
    assertEquals("123", copy);
    view.setLength(0);
    assertSame(copy, cache.value(1));
    assertSame(copy, cache.valueView(1));
  }

  @Test
  void visitsTransientValuesAsStrings() {
    CarrierCache cache = new CarrierCache();
    cache.acceptTransient(
        HeaderSchema.X_DATADOG_PARENT_ID, "x-datadog-parent-id", "456x".subSequence(0, 3));
    List<Object> seen = new ArrayList<>();
    cache.forEachKeyValue(cache, (key, value) -> seen.add(value));
    assertEquals(1, seen.size());
    assertEquals("456", seen.get(0));
    assertTrue(seen.get(0) instanceof String);
  }

  @Test
  void keepsTransientValuesPastItsInitialCapacity() {
    CarrierCache cache = new CarrierCache();
    for (int i = 0; i < 20; i++) {
      if (i % 2 == 0) {
        cache.acceptTransient(HeaderSchema.X_DATADOG_TRACE_ID, "t" + i, new StringBuilder("v" + i));
      } else {
        cache.accept("k" + i, "v" + i);
      }
    }
    for (int i = 0; i < 20; i++) {
      assertEquals("v" + i, cache.value(i));
      assertEquals(i % 2 == 0 ? HeaderSchema.X_DATADOG_TRACE_ID : null, cache.header(i));
    }
  }

  @Test
  void transientValueFirstThenStrings() {
    CarrierCache cache = new CarrierCache();
    cache.accept("a", "1");
    cache.acceptTransient(HeaderSchema.X_DATADOG_TRACE_ID, "b", new StringBuilder("2"));
    cache.accept("c", "3");
    assertEquals("1", cache.valueView(0));
    assertEquals("3", cache.valueView(2));
    assertEquals("2", cache.value(1));
  }

  @Test
  void nullValuesWithAndWithoutViews() {
    CarrierCache cache = new CarrierCache();
    cache.accept("a", null);
    assertNull(cache.value(0));
    assertNull(cache.valueView(0));
    cache.acceptTransient(HeaderSchema.X_DATADOG_TRACE_ID, "b", new StringBuilder("2"));
    cache.accept("c", null);
    assertNull(cache.value(2));
    assertNull(cache.valueView(2));
    assertNull(cache.value(0));
  }

  @Test
  void indexedCarrierViewsDefaultToValues() {
    IndexedCarrier carrier =
        new IndexedCarrier() {
          @Override
          public int size() {
            return 1;
          }

          @Override
          public String key(int index) {
            return "k";
          }

          @Override
          public String value(int index) {
            return "v";
          }

          @Override
          public HeaderSchema header(int index) {
            return null;
          }
        };
    assertEquals("v", carrier.valueView(0));
  }

  @Test
  void emptyCache() {
    CarrierCache cache = new CarrierCache();
    List<String> seen = new ArrayList<>();
    cache.forEachKeyValue(cache, (key, value) -> seen.add(key));
    assertEquals(0, cache.size());
    assertEquals(0, seen.size());
  }
}
