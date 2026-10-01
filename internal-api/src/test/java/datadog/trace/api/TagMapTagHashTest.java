package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The 64-bit tag hash on {@link TagMap.Entry}: a known tag's hash is its id, a custom tag's is its
 * name hash in the low 32 bits.
 */
class TagMapTagHashTest {
  @Test
  void aKnownTagHashesToItsIdHoweverItIsSet() {
    TagMap.Entry byId = TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_ID, "GET");
    TagMap.Entry byName = TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_NAME, "GET");
    TagMap.Entry byOtelName = TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_OTEL_NAME, "GET");

    assertEquals(KnownTags.HTTP_METHOD_ID, byId.tagHash);
    assertEquals(KnownTags.HTTP_METHOD_ID, byName.tagHash);
    assertEquals(KnownTags.HTTP_METHOD_ID, byOtelName.tagHash);
    assertEquals(KnownTags.HTTP_METHOD_ID, byName.tagId());
  }

  @Test
  void aCustomTagHashesToItsNameWithNoId() {
    TagMap.Entry custom = TagMap.Entry.newAnyEntry("my.custom.tag", "value");

    assertEquals(0L, custom.tagHash >>> 32);
    assertEquals(0L, custom.tagId());
    assertEquals(TagMap.Entry.tagHashOf("my.custom.tag"), custom.tagHash);
  }

  @Test
  void theBucketHashOfACustomTagIsUnchanged() {
    assertEquals(
        TagMap.Entry._hash("my.custom.tag"),
        TagMap.Entry.bucketHash(TagMap.Entry.tagHashOf("my.custom.tag")));
  }

  @Test
  void theBucketHashOfAKnownTagCarriesItsSerial() {
    // Folding from bit 32 would leave the serial above any bucket mask; it must come from bit 48.
    assertNotEquals(
        TagMap.Entry.bucketHash(KnownTags.HTTP_METHOD_ID) & 0xFF,
        TagMap.Entry.bucketHash(KnownTags.HTTP_ROUTE_ID) & 0xFF);
  }

  @Test
  void lookupsFindEntriesSetEitherWay() {
    TagMap map = TagMap.create();
    map.set(KnownTags.PEER_PORT_ID, 5432);
    map.set("my.custom.tag", "value");

    assertEquals(5432, map.getEntry(KnownTags.PEER_PORT_NAME).intValue());
    assertEquals("value", map.getString("my.custom.tag"));
    assertEquals(5432, map.getAndRemove(KnownTags.PEER_PORT_NAME).intValue());
    assertEquals(null, map.getEntry(KnownTags.PEER_PORT_NAME));
  }
}
