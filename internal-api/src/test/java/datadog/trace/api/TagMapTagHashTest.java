package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 64-bit tag hash on {@link TagMap.Entry}: a known tag's hash is its id, a custom tag's is its
 * name hash in the low 32 bits.
 */
class TagMapTagHashTest {
  @Test
  void aKnownTagHashesToItsIdHoweverItIsSet() {
    TagMap.Entry byId = TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_ID, "GET");
    TagMap.Entry byName = TagMap.anyEntryFor(KnownTags.HTTP_METHOD_NAME, "GET");
    TagMap.Entry byOtelName = TagMap.anyEntryFor(KnownTags.HTTP_METHOD_OTEL_NAME, "GET");

    assertEquals(KnownTags.HTTP_METHOD_ID, byId.tagHash);
    assertEquals(KnownTags.HTTP_METHOD_ID, byName.tagHash);
    assertEquals(KnownTags.HTTP_METHOD_ID, byOtelName.tagHash);
    assertEquals(KnownTags.HTTP_METHOD_ID, byName.tagId());
  }

  @Test
  void theNameFactoriesAreForCustomTagsOnly() {
    // Entry's name factories skip the registry, so a known name must come in through its id;
    // anyEntryFor and friends resolve a name first. (Tests run with assertions enabled.)
    assertThrows(
        AssertionError.class, () -> TagMap.Entry.newAnyEntry(KnownTags.HTTP_METHOD_NAME, "GET"));
    assertEquals(0L, TagMap.Entry.newAnyEntry("my.custom.tag", "value").tagId());
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
  void noKnownTagFoldsToTheVacantSlotHash() throws IllegalAccessException {
    // BucketGroup treats a zero hash as a vacant slot; _dd.djm.enabled once folded to zero.
    for (long tagId : knownTagIds()) {
      assertNotEquals(0, TagMap.Entry.bucketHash(tagId), Long.toHexString(tagId));
    }
    assertNotEquals(0, TagMap.Entry.bucketHash(KnownTags.DD_DJM_ENABLED_ID));
  }

  @Test
  void everyKnownTagSurvivesBucketCollisionsAndCopies() throws IllegalAccessException {
    List<Long> tagIds = knownTagIds();
    TagMap map = TagMap.create();
    for (long tagId : tagIds) {
      map.set(tagId, "value");
    }
    TagMap copy = map.copy();

    assertEquals(tagIds.size(), map.size());
    assertEquals(tagIds.size(), copy.size());
    for (long tagId : tagIds) {
      String name = KnownTagCodec.nameOf(tagId);
      assertEquals("value", map.getEntry(tagId).objectValue(), name);
      assertEquals("value", copy.getEntry(tagId).objectValue(), name);
    }
  }

  /**
   * A Datadog name shared by a tag per direction names no single tag, but a span holds only one of
   * them, so a lookup by that name finds whichever the map holds.
   */
  @Test
  void aSharedNameFindsWhicheverOfItsTagsTheMapHolds() {
    TagMap inbound = TagMap.create();
    inbound.set(KnownTags.PEER_PORT_INBOUND_ID, 1234);
    TagMap outbound = TagMap.create();
    outbound.set(KnownTags.PEER_PORT_OUTBOUND_ID, 5432);

    assertEquals(1234, inbound.getEntry(Tags.PEER_PORT).intValue());
    assertEquals(5432, outbound.getEntry(Tags.PEER_PORT).intValue());
    assertEquals(5432, outbound.get(Tags.PEER_PORT));
    assertTrue(outbound.containsKey(Tags.PEER_PORT));
    assertEquals(5432, outbound.copy().get(Tags.PEER_PORT));
    assertNull(outbound.getEntry(KnownTags.PEER_PORT_INBOUND_ID));
  }

  @Test
  void aSharedNameSetWithoutADirectionIsACustomTag() {
    TagMap map = TagMap.create();
    map.set(Tags.PEER_PORT, 5432);

    assertEquals(5432, map.getEntry(Tags.PEER_PORT).intValue());
    assertEquals(0L, map.getEntry(Tags.PEER_PORT).tagId());
  }

  @Test
  void removingASharedNameRemovesWhicheverOfItsTagsTheMapHolds() {
    TagMap map = TagMap.create();
    map.set(KnownTags.PEER_PORT_OUTBOUND_ID, 5432);

    assertEquals(5432, map.getAndRemove(Tags.PEER_PORT).intValue());
    assertNull(map.getEntry(KnownTags.PEER_PORT_OUTBOUND_ID));
    assertEquals(0, map.size());
  }

  @Test
  void aSharedNameReadsThroughToTheParent() {
    TagMap parent = TagMap.create();
    parent.set(KnownTags.PEER_PORT_OUTBOUND_ID, 5432);
    TagMap child = TagMap.createFromParent(parent.freeze());

    assertEquals(5432, child.get(Tags.PEER_PORT));
    child.remove(Tags.PEER_PORT);
    assertNull(child.get(Tags.PEER_PORT));
  }

  private static List<Long> knownTagIds() throws IllegalAccessException {
    List<Long> tagIds = new ArrayList<>();
    for (Field field : KnownTags.class.getFields()) {
      if (field.getType() == long.class && Modifier.isStatic(field.getModifiers())) {
        tagIds.add(field.getLong(null));
      }
    }
    return tagIds;
  }

  @Test
  void lookupsFindEntriesSetEitherWay() {
    TagMap map = TagMap.create();
    map.set(KnownTags.HTTP_STATUS_CODE_ID, 5432);
    map.set("my.custom.tag", "value");

    assertEquals(5432, map.getEntry(KnownTags.HTTP_STATUS_CODE_NAME).intValue());
    assertEquals("value", map.getString("my.custom.tag"));
    assertEquals(5432, map.getAndRemove(KnownTags.HTTP_STATUS_CODE_NAME).intValue());
    assertEquals(null, map.getEntry(KnownTags.HTTP_STATUS_CODE_NAME));
  }
}
