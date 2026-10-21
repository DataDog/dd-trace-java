package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PathwayContextCodecCacheTest {
  @Test
  void encodedHitAndMiss() {
    long hash = 0x1234567890abcdefL;
    assertNull(PathwayContextCodecCache.getEncoded(hash, 1000L, 1005L));
    PathwayContextCodecCache.putEncoded(hash, 1000L, 1005L, "value");
    assertEquals("value", PathwayContextCodecCache.getEncoded(hash, 1000L, 1005L));
  }

  @Test
  void encodedMissesWhenOnlyOneFieldDiffers() {
    long hash = 0x7777L;
    PathwayContextCodecCache.putEncoded(hash, 2000L, 2010L, "v");
    assertNull(PathwayContextCodecCache.getEncoded(hash + 1, 2000L, 2010L));
    assertNull(PathwayContextCodecCache.getEncoded(hash, 2001L, 2010L));
    assertNull(PathwayContextCodecCache.getEncoded(hash, 2000L, 2011L));
  }

  @Test
  void encodedNeverReturnsAnotherKeysValue() {
    long hash = 99L;
    String latest = null;
    // more keys than slots guarantees slot collisions
    for (long i = 0; i < 1000; i++) {
      PathwayContextCodecCache.putEncoded(hash, i, i, "v" + i);
      latest = "v" + i;
    }
    assertEquals(latest, PathwayContextCodecCache.getEncoded(hash, 999L, 999L));
    for (long i = 0; i < 999; i++) {
      String value = PathwayContextCodecCache.getEncoded(hash, i, i);
      if (value != null) {
        assertEquals("v" + i, value);
      }
    }
  }

  @Test
  void decodedHitAndMiss() {
    assertNull(PathwayContextCodecCache.getDecoded("some-unseen-value"));
    PathwayContextCodecCache.putDecoded("abc-hit", 1L, 2L, 3L);
    PathwayContextCodecCache.Decoded decoded = PathwayContextCodecCache.getDecoded("abc-hit");
    assertNotNull(decoded);
    assertEquals(1L, decoded.hash);
    assertEquals(2L, decoded.pathwayStartMillis);
    assertEquals(3L, decoded.edgeStartMillis);
  }

  @Test
  void decodedHitsOnEqualContentDistinctInstance() {
    PathwayContextCodecCache.putDecoded(new String("equal-content"), 5L, 6L, 7L);
    PathwayContextCodecCache.Decoded decoded =
        PathwayContextCodecCache.getDecoded(new String("equal-content"));
    assertNotNull(decoded);
    assertEquals(5L, decoded.hash);
  }

  @Test
  void decodedCollisionsNeverReturnAnotherKeysValue() {
    for (int i = 0; i < 1000; i++) {
      PathwayContextCodecCache.putDecoded("key" + i, i, i, i);
    }
    for (int i = 0; i < 1000; i++) {
      PathwayContextCodecCache.Decoded decoded = PathwayContextCodecCache.getDecoded("key" + i);
      if (decoded != null) {
        assertEquals("key" + i, decoded.value);
        assertEquals(i, decoded.hash);
      }
    }
  }

  @Test
  void tablesAreIndependent() {
    PathwayContextCodecCache.putEncoded(424242L, 1L, 1L, "only-encoded-value");
    assertNull(PathwayContextCodecCache.getDecoded("only-encoded-value"));
  }
}
