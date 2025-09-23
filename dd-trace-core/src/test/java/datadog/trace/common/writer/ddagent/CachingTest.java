package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CachingTest {
  @Test
  public void capacity() {
    // exact
    assertEquals(64, Caching.cacheSizeFor(64));
    assertEquals(128, Caching.cacheSizeFor(128));

    // next power of 2
    assertEquals(64, Caching.cacheSizeFor(63));
    assertEquals(64, Caching.cacheSizeFor(33));
  }

  @Test
  public void marking_exact() {
    int[] marks = new int[Caching.cacheSizeFor(32)];

    assertFalse(Caching.mark(marks, 31));
    assertTrue(Caching.mark(marks, 31));

    // should have been reset
    assertFalse(Caching.mark(marks, 31));
  }

  @Test
  public void marking_collision() {
    // deliberately using tiny array to force collision
    int[] marks = new int[1];

    // powers of 2 to reduce false positives in test
    assertFalse(Caching.mark(marks, 128));
    assertFalse(Caching.mark(marks, 64));

    assertTrue(Caching.mark(marks, 128));
    // should now be reset
    assertFalse(Caching.mark(marks, 64));
  }
}
