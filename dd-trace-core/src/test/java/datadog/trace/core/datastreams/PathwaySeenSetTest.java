package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PathwaySeenSetTest {

  @Test
  void insertedHashesAreSeenUntilCleared() {
    PathwaySeenSet seenSet = new PathwaySeenSet();

    assertFalse(seenSet.contains(42L));
    seenSet.insert(42L);
    seenSet.insert(0L);

    assertTrue(seenSet.contains(42L));
    assertTrue(seenSet.contains(0L));
    assertFalse(seenSet.contains(43L));

    seenSet.clear();

    assertFalse(seenSet.contains(42L));
    assertFalse(seenSet.contains(0L));
  }

  @Test
  void hashesThatDoNotFitAreNeverSeenAndAreCounted() {
    PathwaySeenSet seenSet = new PathwaySeenSet();
    int keys = 2 * PathwaySeenSet.CAPACITY;
    for (long hash = 1; hash <= keys; hash++) {
      seenSet.insert(hash);
    }

    long unseen = 0;
    for (long hash = 1; hash <= keys; hash++) {
      if (!seenSet.contains(hash)) {
        unseen++;
      }
    }

    assertTrue(unseen >= PathwaySeenSet.CAPACITY);
    assertEquals(unseen, seenSet.drainOverflows());
    assertEquals(0, seenSet.drainOverflows());
  }
}
