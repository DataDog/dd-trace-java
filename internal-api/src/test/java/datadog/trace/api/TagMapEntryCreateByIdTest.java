package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link TagMap.Entry#create(long, Object)} and its overloads: creating an entry by tag id. */
class TagMapEntryCreateByIdTest {
  @Test
  void matchesCreateByNameForEveryValueType() {
    assertEquivalent(
        TagMap.Entry.create(KnownTags.COMPONENT_NAME, (Object) "okhttp"),
        TagMap.Entry.create(KnownTags.COMPONENT_ID, (Object) "okhttp"));
    assertEquivalent(
        TagMap.Entry.create(KnownTags.PEER_HOSTNAME_NAME, (CharSequence) "db.internal"),
        TagMap.Entry.create(KnownTags.PEER_HOSTNAME_ID, (CharSequence) "db.internal"));
    assertEquivalent(
        TagMap.Entry.create(KnownTags.DD_PROFILING_ENABLED_NAME, true),
        TagMap.Entry.create(KnownTags.DD_PROFILING_ENABLED_ID, true));
    assertEquivalent(
        TagMap.Entry.create(KnownTags.HTTP_STATUS_CODE_NAME, 5432),
        TagMap.Entry.create(KnownTags.HTTP_STATUS_CODE_ID, 5432));
    assertEquivalent(
        TagMap.Entry.create(KnownTags.HTTP_RESEND_COUNT_NAME, 2L),
        TagMap.Entry.create(KnownTags.HTTP_RESEND_COUNT_ID, 2L));
    assertEquivalent(
        TagMap.Entry.create(KnownTags.DB_USER_NAME, 1.5f),
        TagMap.Entry.create(KnownTags.DB_USER_ID, 1.5f));
    assertEquivalent(
        TagMap.Entry.create(KnownTags.DB_POOL_NAME, 2.5d),
        TagMap.Entry.create(KnownTags.DB_POOL_NAME_ID, 2.5d));
  }

  @Test
  void aNullOrEmptyValueCreatesNoEntry() {
    assertNull(TagMap.Entry.create(KnownTags.COMPONENT_ID, (Object) null));
    assertNull(TagMap.Entry.create(KnownTags.COMPONENT_ID, (Object) ""));
    assertNull(TagMap.Entry.create(KnownTags.COMPONENT_ID, (CharSequence) null));
    assertNull(TagMap.Entry.create(KnownTags.COMPONENT_ID, (CharSequence) ""));
  }

  @Test
  void anUnknownIdIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> TagMap.Entry.create(0L, (Object) "value"));
    assertThrows(
        IllegalArgumentException.class,
        () -> TagMap.Entry.create(KnownTagCodec.makeTagId(9999), 1));
  }

  /** Same tag, id, type and value -- the two creation paths build equivalent entries. */
  private static void assertEquivalent(TagMap.Entry byName, TagMap.Entry byId) {
    assertEquals(byName.tag(), byId.tag());
    assertEquals(byName.tagId(), byId.tagId());
    assertEquals(byName.type(), byId.type());
    assertEquals(byName.objectValue(), byId.objectValue());
  }
}
