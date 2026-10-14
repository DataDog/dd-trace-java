package datadog.trace.api.cache;

import static datadog.trace.test.util.ThreadUtils.runConcurrently;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RadixTreeCacheTest extends DDJavaSpecification {

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17})
  void cachedValuesAreEqualToAutoboxedValues(int primitive) {
    RadixTreeCache<Integer> cache = new RadixTreeCache<>(4, 4, value -> value);

    Integer cached = cache.get(primitive);

    assertEquals(Integer.valueOf(primitive), cached);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17})
  void cachedValuesAreEqualToComputedValues(int primitive) {
    RadixTreeCache<String> cache = new RadixTreeCache<>(4, 4, String::valueOf);

    String cached = cache.get(primitive);

    assertEquals(String.valueOf(primitive), cached);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17})
  void cachedValuesAreEqualToComputedValuesWithPrecomputing(int primitive) {
    RadixTreeCache<String> cache = new RadixTreeCache<>(4, 4, String::valueOf, -1, 9);

    String cached = cache.get(primitive);

    assertEquals(String.valueOf(primitive), cached);
  }

  @Test
  void concurrencyTest() throws Throwable {
    RadixTreeCache<String> cache = new RadixTreeCache<>(256, 256, String::valueOf, -1, 9);

    assertTrue(
        runConcurrently(
            8,
            10_000,
            () -> {
              int value = ThreadLocalRandom.current().nextInt(70_000);
              assertEquals(String.valueOf(value), cache.get(value));
            }));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 200, 201, 404, 329, 599, 700})
  void cacheHttpStatuses(int status) {
    assertEquals(
        Integer.toString(status), String.valueOf(RadixTreeCache.HTTP_STATUSES.get(status)));
  }
}
