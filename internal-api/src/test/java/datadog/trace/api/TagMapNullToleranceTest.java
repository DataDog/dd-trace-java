package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the Entry-pathway null-tolerance contract: {@code create(...)} returns null for a null or
 * empty value, and the {@code Entry} sinks ({@code set(Entry)} / {@code getAndSet(Entry)}) treat
 * null as a no-op -- so a null/empty value flows through as "no tag" without any caller guarding.
 */
class TagMapNullToleranceTest {

  @Test
  void createReturnsNullForNullOrEmptyValue() {
    // CharSequence overload: null or empty -> null
    assertNull(TagMap.Entry.create("k", (CharSequence) null));
    assertNull(TagMap.Entry.create("k", ""));

    // Object overload: null -> null, and an empty String typed as Object -> null (runtime check,
    // so the convention holds regardless of the static type at the call site)
    assertNull(TagMap.Entry.create("k", (Object) null));
    assertNull(TagMap.Entry.create("k", (Object) ""));

    // Non-empty values still produce an entry, whatever the static type
    assertNotNull(TagMap.Entry.create("k", "v"));
    assertNotNull(TagMap.Entry.create("k", (Object) "v"));
    // Non-CharSequence Object has no notion of "empty" -> always an entry
    assertNotNull(TagMap.Entry.create("k", (Object) Integer.valueOf(0)));
  }

  @Test
  void getAndSetToleratesNullEntry() {
    TagMap map = TagMap.create();
    assertNull(map.getAndSet((TagMap.Entry) null), "null entry is a no-op returning null");
    assertEquals(0, map.size(), "no tag added");
  }

  @Test
  void setToleratesNullReader() {
    TagMap map = TagMap.create();
    map.set((TagMap.EntryReader) null); // must not throw
    assertEquals(0, map.size(), "no tag added");
  }

  @Test
  void nullOrEmptyFlowsThroughTheEntryPathwayAsNoTag() {
    TagMap map = TagMap.create();

    // The seamless case: create(...) -> set(Entry) with a null/empty value, no caller guard.
    map.set(TagMap.Entry.create("empty", ""));
    map.set(TagMap.Entry.create("emptyObj", (Object) ""));
    map.set(TagMap.Entry.create("nul", (CharSequence) null));
    assertEquals(0, map.size(), "null/empty values leave no tags behind");
    assertFalse(map.containsKey("empty"));

    // A real value still lands.
    map.set(TagMap.Entry.create("present", "v"));
    assertEquals(1, map.size());
    assertTrue(map.containsKey("present"));
  }
}
