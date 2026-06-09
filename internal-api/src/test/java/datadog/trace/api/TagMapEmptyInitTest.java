package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic: is TagMap.EMPTY null when OptimizedTagMap is the first TagMap-related class touched
 * in the JVM? Forked so nothing else initializes TagMap first.
 */
public class TagMapEmptyInitTest {
  @Test
  void emptyNotNull_whenOptimizedInitsFirst() {
    // force OptimizedTagMap to initialize before the TagMap interface
    OptimizedTagMap m = new OptimizedTagMap();
    m.set("x", "y");

    System.out.println("OptimizedTagMap.EMPTY=" + OptimizedTagMap.EMPTY);
    System.out.println("TagMap.EMPTY=" + TagMap.EMPTY);

    assertNotNull(OptimizedTagMap.EMPTY, "OptimizedTagMap.EMPTY null");
    assertNotNull(TagMap.EMPTY, "TagMap.EMPTY null");
    assertNotNull(
        TagMap.fromMapImmutable(Collections.emptyMap()), "fromMapImmutable(empty) returned null");
    assertNotNull(TagMap.ledger().buildImmutable(), "empty ledger buildImmutable returned null");
  }
}
