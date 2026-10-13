package datadog.trace.api;

import static datadog.trace.api.KnownTags.PEER_PORT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A name declared per direction ({@code peer.port}) is one tag to a map: a write by name and a
 * write by id land on the same entry, in a map and across a frozen parent. A direction-scoped
 * OpenTelemetry name ({@code server.address}) is stored as written, for the span to resolve.
 */
class TagMapDirectionalNameTest {
  @Test
  void aWriteByNameAndAWriteByIdAreOneEntry() {
    TagMap map = TagMap.create();
    map.set("peer.port", 80);
    map.set(PEER_PORT_ID, 81);

    assertEquals(81, map.getEntry(PEER_PORT_ID).intValue());
    assertEquals(81, map.getEntry("peer.port").intValue());
    assertEquals(PEER_PORT_ID, map.getEntry("peer.port").tagId());
    assertEquals(1, entriesNamed(map, "peer.port"));
  }

  @Test
  void aChildWriteByIdShadowsAParentWriteByName() {
    TagMap parent = TagMap.create();
    parent.set("peer.port", 80);
    parent.freeze();

    TagMap child = TagMap.createFromParent(parent);
    child.set(PEER_PORT_ID, 81);

    assertEquals(81, child.getEntry("peer.port").intValue());
    assertEquals(1, entriesNamed(child, "peer.port"));
  }

  @Test
  void aRemovalByNameHidesTheParentsEntry() {
    TagMap parent = TagMap.create();
    parent.set(PEER_PORT_ID, 5432);
    TagMap child = TagMap.createFromParent(parent.freeze());

    assertEquals(5432, child.get("peer.port"));
    child.remove("peer.port");
    assertNull(child.get("peer.port"));
    assertNull(child.getEntry(PEER_PORT_ID));
  }

  @Test
  void entriesThatAreEqualHashAlike() {
    TagMap byName = TagMap.create();
    byName.set("peer.port", 80);
    TagMap byId = TagMap.create();
    byId.set(PEER_PORT_ID, 80);

    TagMap.Entry a = byName.getEntry("peer.port");
    TagMap.Entry b = byId.getEntry(PEER_PORT_ID);

    if (a.equals(b)) {
      assertEquals(a.hashCode(), b.hashCode());
    }
    assertEquals(b.tagId(), a.tagId());
  }

  @Test
  void aDirectionScopedOpenTelemetryNameIsStoredAsWritten() {
    TagMap map = TagMap.create();
    map.set("server.address", "example.com");

    assertEquals("example.com", map.get("server.address"));
    assertEquals(0L, map.getEntry("server.address").tagId());
    assertNull(map.getEntry(KnownTags.PEER_HOSTNAME_ID));
  }

  private static int entriesNamed(TagMap map, String name) {
    int count = 0;
    for (TagMap.EntryReader entry : map) {
      if (name.equals(entry.tag())) {
        count++;
      }
    }
    return count;
  }
}
