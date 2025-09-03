package datadog.trace.common.writer.ddagent;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SimpleUtf8CacheTest {
  @ParameterizedTest
  @ValueSource(strings = {"foo", "bar", "baz", "quux"})
  public void getUtf8(String value) {
    SimpleUtf8Cache cache = new SimpleUtf8Cache();

    for (int i = 0; i < 10; ++i) {
      byte[] valueUtf8 = cache.getUtf8(value);
      assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), valueUtf8);
    }
  }

  @Test
  public void caching() {
    SimpleUtf8Cache cache = new SimpleUtf8Cache();

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

    assertNotEquals(0, cache.hits);
  }

  @Test
  public void fuzz() {
    Random random = ThreadLocalRandom.current();

    int hits = 0;

    SimpleUtf8Cache cache = new SimpleUtf8Cache();
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

      hits += cache.hits;

      printStats(cache);
    }

    assertNotEquals(0, hits);
  }

  @Test
  public void bigString_dont_cache() {
    String lorem = "Lorem ipsum dolor sit amet";
    while (lorem.length() < 100) {
      lorem += lorem;
    }
    byte[] expected = lorem.getBytes(StandardCharsets.UTF_8);

    SimpleUtf8Cache cache = new SimpleUtf8Cache();
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
    assertEquals(0, cache.hits);
  }

  static final String[] TAGS = {"foo", "bar", "baz"};

  static final String[] BASE_STRINGS = {"Hello", "world", "foo", "bar", "baz", "quux"};

  static final String nextTag() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    int tagIndex = random.nextInt(TAGS.length + 1);
    if (tagIndex >= TAGS.length) {
      return "tag-" + Integer.toString(random.nextInt());
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

  static final void printStats(SimpleUtf8Cache cache) {
    System.out.printf(
        "eden hits: %5d\tpromotion hits: %5d\tpromotions: %5d\tearly: %5d\tlocal evictions: %5d\tglobal evictions: %5d%n",
        cache.hits, cache.evictions);
  }
}
