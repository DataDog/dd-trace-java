package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@link TagMap.Entry#create(String, Object)} and its overloads: an entry is built with no span, so
 * a name whose tag depends on the span's direction is rejected.
 */
class TagMapEntryCreateByNameTest {
  @TableTest({
    "scenario                     | name                ",
    "shared Datadog name          | peer.port           ",
    "OTel name, both directions   | server.address      ",
    "OTel name, outbound only     | server.port         ",
    "OTel name, inbound only      | client.port         ",
    "OTel name, inbound only (ip) | network.peer.address"
  })
  void aNameWhoseTagDependsOnDirectionIsRejected(String name) {
    assertThrows(IllegalArgumentException.class, () -> TagMap.Entry.create(name, 5432));
    assertThrows(IllegalArgumentException.class, () -> TagMap.Entry.create(name, "value"));
    assertThrows(IllegalArgumentException.class, () -> TagMap.Entry.create(name, (Object) "value"));
  }

  @Test
  void aKnownNameCreatesTheKnownTag() {
    assertEquals(
        KnownTags.HTTP_METHOD_ID, TagMap.Entry.create("http.request.method", "GET").tagId());
    assertEquals(
        KnownTags.HTTP_METHOD_NAME, TagMap.Entry.create("http.request.method", "GET").tag());
  }

  @Test
  void aCustomNameCreatesACustomTag() {
    TagMap.Entry entry = TagMap.Entry.create("my.custom.tag", 3);

    assertEquals(0L, entry.tagId());
    assertEquals("my.custom.tag", entry.tag());
    assertEquals(3, entry.intValue());
  }

  @Test
  void theIdOfATagDeclaredPerDirectionCreatesIt() {
    TagMap.Entry entry = TagMap.Entry.create(KnownTags.PEER_PORT_OUTBOUND_ID, (Object) 5432);

    assertEquals(KnownTags.PEER_PORT_OUTBOUND_ID, entry.tagId());
    assertEquals("peer.port", entry.tag());
  }
}
