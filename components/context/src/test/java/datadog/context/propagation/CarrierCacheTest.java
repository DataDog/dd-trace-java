package datadog.context.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
  void emptyCache() {
    CarrierCache cache = new CarrierCache();
    List<String> seen = new ArrayList<>();
    cache.forEachKeyValue(cache, (key, value) -> seen.add(key));
    assertEquals(0, cache.size());
    assertEquals(0, seen.size());
  }
}
