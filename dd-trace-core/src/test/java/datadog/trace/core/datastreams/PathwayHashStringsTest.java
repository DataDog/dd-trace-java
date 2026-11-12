package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PathwayHashStringsTest {
  @Test
  void matchesUnsignedString() {
    Random random = new Random(7);
    long[] edges = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
    for (long hash : edges) {
      assertEquals(Long.toUnsignedString(hash), PathwayHashStrings.toUnsignedString(hash));
    }
    for (int i = 0; i < 10000; i++) {
      long hash = random.nextLong();
      assertEquals(Long.toUnsignedString(hash), PathwayHashStrings.toUnsignedString(hash));
    }
  }

  @Test
  void repeatedLookupReturnsCachedInstance() {
    long hash = 0xdeadbeefcafeL;
    assertSame(
        PathwayHashStrings.toUnsignedString(hash), PathwayHashStrings.toUnsignedString(hash));
  }

  @Test
  void collidingHashesStayCorrect() {
    long a = 5L;
    long b = a + (1L << 7); // same slot: (hash ^ hash>>>32) & 127
    for (int i = 0; i < 3; i++) {
      assertEquals("5", PathwayHashStrings.toUnsignedString(a));
      assertEquals("133", PathwayHashStrings.toUnsignedString(b));
    }
  }
}
