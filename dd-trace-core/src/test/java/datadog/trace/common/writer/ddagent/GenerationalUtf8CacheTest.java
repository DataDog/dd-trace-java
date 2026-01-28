package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class GenerationalUtf8CacheTest {
  static final GenerationalUtf8Cache create() {
    return new GenerationalUtf8Cache(64, 128);
  }

  @ParameterizedTest
  @ValueSource(strings = {"foo", "bar", "baz", "quux"})
  public void getUtf8(String value) {
    GenerationalUtf8Cache cache = create();

    for (int i = 0; i < 10; ++i) {
      byte[] valueUtf8 = cache.getUtf8(value);
      assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), valueUtf8);
    }
  }

  @Test
  public void capacity() {
    GenerationalUtf8Cache cache = new GenerationalUtf8Cache(192);
    assertEquals(64, cache.edenCapacity());
    assertEquals(128, cache.tenuredCapacity());
  }

  @Test
  public void maxCapacity() {
    GenerationalUtf8Cache cache =
        new GenerationalUtf8Cache(
            GenerationalUtf8Cache.MAX_EDEN_CAPACITY + 1,
            GenerationalUtf8Cache.MAX_TENURED_CAPACITY + 1);

    assertEquals(GenerationalUtf8Cache.MAX_EDEN_CAPACITY, cache.edenCapacity());
    assertEquals(GenerationalUtf8Cache.MAX_TENURED_CAPACITY, cache.tenuredCapacity());
  }

  @Test
  public void maxCapacity_combined() {
    GenerationalUtf8Cache cache =
        new GenerationalUtf8Cache(
            GenerationalUtf8Cache.MAX_EDEN_CAPACITY
                + GenerationalUtf8Cache.MAX_TENURED_CAPACITY
                + 2);

    assertEquals(GenerationalUtf8Cache.MAX_EDEN_CAPACITY, cache.edenCapacity());
    assertEquals(GenerationalUtf8Cache.MAX_TENURED_CAPACITY, cache.tenuredCapacity());
  }

  @Test
  public void caching() {
    GenerationalUtf8Cache cache = create();

    String value = "bar";
    byte[] expected = value.getBytes(StandardCharsets.UTF_8);

    byte[] first = cache.getUtf8(value);
    assertArrayEquals(expected, first);

    // first request isn't cached - to avoid burning slots
    byte[] second = cache.getUtf8(value);
    assertArrayEquals(expected, second);
    assertNotSame(first, second);

    // after first request, the entry should be cached
    byte[] third = cache.getUtf8(value);
    assertArrayEquals(expected, third);
    assertSame(second, third);

    assertNotEquals(0, cache.edenHits);
  }

  @Test
  public void promotion() {
    GenerationalUtf8Cache cache = create();

    String value = "bar";
    byte[] expected = value.getBytes(StandardCharsets.UTF_8);

    byte[] first = cache.getUtf8(value);
    assertArrayEquals(expected, first);

    byte[] second = cache.getUtf8(value);
    assertArrayEquals(expected, second);
    assertNotSame(second, first);

    while (cache.promotions == 0) {
      byte[] cached = cache.getUtf8(value);
      assertArrayEquals(expected, cached);
      assertSame(cached, second);
    }

    assertNotEquals(0, cache.edenHits);

    for (int i = 0; i < 10; ++i) {
      byte[] cached = cache.getUtf8(value);

      assertArrayEquals(expected, cached);
      assertSame(cached, second);
    }

    assertNotEquals(0, cache.tenuredHits);
  }

  @Test
  public void fuzz() {
    Random random = ThreadLocalRandom.current();

    int edenHits = 0;
    int promotedHits = 0;

    GenerationalUtf8Cache cache = create();
    for (int i = 0; i < 1_000; ++i) {
      cache.recalibrate();

      int cycles = 500 + random.nextInt(2_000);
      for (int j = 0; j < cycles; ++j) {
        String nextTag = nextTag();
        String nextValue = nextValue();
        byte[] nextExpected = nextValue.getBytes(StandardCharsets.UTF_8);

        byte[] nextValueUtf8 = cache.getUtf8(nextValue);
        assertArrayEquals(nextExpected, nextValueUtf8);
      }

      edenHits += cache.edenHits;
      promotedHits += cache.tenuredHits;

      printStats(cache);
    }

    assertNotEquals(0, edenHits);
    assertNotEquals(0, promotedHits);
  }

  @Test
  public void bigString_dont_cache() {
    String lorem = "Lorem ipsum dolor sit amet";
    while (lorem.length() <= GenerationalUtf8Cache.MAX_ENTRY_LEN) {
      lorem += lorem;
    }
    byte[] expected = lorem.getBytes(StandardCharsets.UTF_8);

    GenerationalUtf8Cache cache = create();
    byte[] first = cache.getUtf8(lorem);
    assertArrayEquals(expected, first);

    byte[] second = cache.getUtf8(lorem);
    assertArrayEquals(expected, second);
    assertNotSame(first, second);

    for (int i = 0; i < 10; ++i) {
      byte[] result = cache.getUtf8(lorem);
      assertArrayEquals(expected, result);

      assertNotSame(first, result);
      assertNotSame(second, result);
    }
    assertEquals(0, cache.edenHits);
    assertEquals(0, cache.tenuredHits);
  }

  static final String[] TAGS = {"foo", "bar", "baz"};

  static final String[] BASE_STRINGS = {"Hello", "world", "foo", "bar", "baz", "quux"};

  static final String nextTag() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    int tagIndex = random.nextInt(TAGS.length + 1);
    if (tagIndex >= TAGS.length) {
      return "tag-" + random.nextInt();
    } else {
      return TAGS[tagIndex];
    }
  }

  static final String nextValue() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    if (random.nextDouble() < 0.1) {
      return Integer.toString(random.nextInt());
    }

    int baseIndex = random.nextInt(BASE_STRINGS.length);
    String baseString = BASE_STRINGS[baseIndex];

    if (random.nextDouble() < 0.2) {
      baseString = baseString.toLowerCase();
    }

    int valueSuffix = random.nextInt(2 * baseIndex + 1);
    return baseString + valueSuffix;
  }

  static final void printStats(GenerationalUtf8Cache cache) {
    System.out.printf(
        "eden hits: %5d\tpromotion hits: %5d\tpromotions: %5d\tearly: %5d\tlocal evictions: %5d\tglobal evictions: %5d%n",
        cache.edenHits,
        cache.tenuredHits,
        cache.promotions,
        cache.earlyPromotions,
        cache.edenEvictions,
        cache.tenuredEvictions);
  }
}
