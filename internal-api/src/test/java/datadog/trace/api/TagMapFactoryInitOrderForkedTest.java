package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The factories can initialize before {@link TagMap} (forked, so this JVM hasn't initialized
 * either). A factory that built its empty map in its own initializer started TagMap's initializer
 * there, which read the still-null {@link TagMapFactory#INSTANCE} and poisoned TagMap for the JVM.
 */
class TagMapFactoryInitOrderForkedTest {
  @Test
  void theFactoriesCanInitializeBeforeTagMap() {
    TagMapFactory hash = HashTagMapFactory.INSTANCE;
    TagMapFactory dense = DenseTagMapFactory.INSTANCE;

    assertTrue(hash.empty().isEmpty());
    assertTrue(dense.empty().isEmpty());
    assertNotNull(TagMap.EMPTY);
    assertSame(TagMap.EMPTY, TagMapFactory.Default.INSTANCE.empty());
    assertNotSame(hash.empty(), dense.empty());
    TagMap map = TagMap.create();
    map.set("a", "b");
    assertEquals("b", map.getString("a"));
  }
}
