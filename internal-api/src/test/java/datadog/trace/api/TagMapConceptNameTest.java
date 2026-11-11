package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Reading a concept by its shared name. A concept's Datadog name ({@code peer.port}) belongs to
 * several ids, so it has no id of its own; a map has no span kind to choose between them, so a read
 * by name finds whichever concept the map holds.
 */
class TagMapConceptNameTest {

  @Test
  void aConceptWrittenByIdReadsBackByItsSharedName() {
    TagMap map = TagMap.create();
    map.set(KnownTags.PEER_CLIENT_PORT, 54321);

    assertEquals(54321, map.getObject("peer.port"));
    assertEquals("54321", map.getString("peer.port"));
    assertEquals(54321, map.get("peer.port"));
    assertTrue(map.containsKey("peer.port"));
    assertEquals(KnownTags.PEER_CLIENT_PORT, map.getEntry("peer.port").tagId());
    assertEquals(KnownTags.PEER_CLIENT_PORT, map.getEntryReader("peer.port").tagId());
  }

  @Test
  void eachConceptSharingTheNameIsFound() {
    TagMap map = TagMap.create();
    map.set(KnownTags.PEER_SERVER_PORT, 443);

    assertEquals(443, map.getObject("peer.port"));
    assertEquals(KnownTags.PEER_SERVER_PORT, map.getEntry("peer.port").tagId());
  }

  @Test
  void aConceptIsFoundInAFrozenCopy() {
    // DDSpanContext.getTags() hands out a frozen copy; by-name assertions read from it.
    TagMap map = TagMap.create();
    map.set(KnownTags.PEER_CLIENT_PORT, 54321);

    TagMap snapshot = map.copy().freeze();

    assertEquals(54321, snapshot.get("peer.port"));
  }

  @Test
  void aPlainTagUnderTheNameIsReadFirst() {
    // A name written before the span's kind was known stays a plain tag until a concept replaces
    // it.
    TagMap map = TagMap.create();
    map.set("peer.port", 8080);

    assertEquals(8080, map.getObject("peer.port"));
    assertEquals(0L, map.getEntry("peer.port").tagId());
  }

  @Test
  void aConceptIsRemovedByItsSharedName() {
    TagMap map = TagMap.create();
    map.set(KnownTags.PEER_CLIENT_PORT, 54321);

    assertTrue(map.remove("peer.port"));

    assertNull(map.getEntry(KnownTags.PEER_CLIENT_PORT));
    assertFalse(map.containsKey("peer.port"));
  }

  @Test
  void aSharedNameWithNoConceptPresentMisses() {
    TagMap map = TagMap.create();
    map.set(KnownTags.HTTP_METHOD, "GET");

    assertNull(map.getObject("peer.port"));
    assertNull(map.getEntry("peer.port"));
    assertFalse(map.containsKey("peer.port"));
  }
}
