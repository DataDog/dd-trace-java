package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How {@link TagMap.Entry#bucketHash} places known and custom tags in the buckets. */
class TagMapBucketHashTest {
  private static final int BUCKET_MASK = 15; // TagMap's 16 buckets

  /** Every known tag id the registry generated. */
  static List<Long> knownTagIds() throws IllegalAccessException {
    List<Long> ids = new ArrayList<>();
    for (Field field : KnownTags.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && field.getType() == long.class
          && field.getName().endsWith("_ID")) {
        ids.add(field.getLong(null));
      }
    }
    return ids;
  }

  @Test
  void knownTagsGetDistinctNonzeroBucketHashes() throws IllegalAccessException {
    List<Long> ids = knownTagIds();
    assertFalse(ids.isEmpty());
    Set<Integer> hashes = new HashSet<>();
    for (long id : ids) {
      int hash = TagMap.Entry.bucketHash(id);
      assertNotEquals(0, hash, "zero marks a vacant BucketGroup slot");
      assertTrue(
          hashes.add(hash), "two known tags share a bucket hash: " + KnownTagCodec.nameOf(id));
    }
  }

  @Test
  void aSlottedKnownTagIsBucketedBySlot() throws IllegalAccessException {
    for (long id : knownTagIds()) {
      int slot = KnownTagCodec.slotOf(id);
      if (slot == KnownTagCodec.NO_SLOT) {
        continue;
      }
      // trace-level tags reuse the span-level slot numbers, so they move to the other half
      int expected = (slot ^ (KnownTagCodec.isTraceLevel(id) ? 8 : 0)) & BUCKET_MASK;
      assertEquals(expected, TagMap.Entry.bucketHash(id) & BUCKET_MASK, KnownTagCodec.nameOf(id));
    }
  }

  @Test
  void tagsWithoutASlotAndCustomTagsKeepTheirHashes() {
    long noSlot = ((long) 5 << 48) | ((long) KnownTagCodec.NO_SLOT << 32);
    assertEquals((int) (noSlot >>> 48) ^ (int) noSlot, TagMap.Entry.bucketHash(noSlot));

    long custom = TagMap.Entry.customHash("my.custom.tag");
    assertEquals((int) custom == 0 ? 0xDD06 : (int) custom, TagMap.Entry.bucketHash(custom));
  }
}
