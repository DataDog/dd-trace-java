package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The JVM's store factory builds working stores, whichever implementation it is. */
class IdStoreFactoryTest {
  private static final IdStoreFactory FACTORY = IdStoreFactory.Default.INSTANCE;

  @Test
  void createdStoreReadsThroughToItsParent() {
    IdStore parent = FACTORY.create(null);
    parent.store(KnownTags.HTTP_ROUTE_ID, "/a");

    IdStore store = FACTORY.create(parent);

    assertEquals("/a", store.load(KnownTags.HTTP_ROUTE_ID));
    store.store(KnownTags.HTTP_METHOD_ID, "GET");
    assertEquals("GET", store.load(KnownTags.HTTP_METHOD_ID));
    assertNull(parent.load(KnownTags.HTTP_METHOD_ID));
  }

  @Test
  void createdEmptyStoreHasNoParent() {
    IdStore store = FACTORY.createEmpty();

    assertTrue(store.isEmpty());
    assertTrue(!store.hasParent());
    store.store(KnownTags.HTTP_ROUTE_ID, "/a");
    assertEquals("/a", store.load(KnownTags.HTTP_ROUTE_ID));
  }
}
