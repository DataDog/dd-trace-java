package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TagMap.Ledger} keyed by id: known tags recorded under their ids, custom tags under their
 * names, read back through {@link TagMap.EntryChangeReader}, with the slot-occupancy and
 * intercepted-id summaries.
 */
class TagMapLedgerByIdTest {
  @Test
  void aKnownTagByIdAndByNameRecordTheSameChange() {
    TagMap byId = TagMap.ledger().set(KnownTags.HTTP_ROUTE_ID, "/users").build();
    TagMap byName = TagMap.ledger().set(KnownTags.HTTP_ROUTE_NAME, "/users").build();

    assertEquals(new HashMap<>(byName), new HashMap<>(byId));
    assertEquals(KnownTags.HTTP_ROUTE_ID, byId.getEntry(KnownTags.HTTP_ROUTE_NAME).tagId());
  }

  @Test
  void anOpenTelemetryNameRecordsItsTagsId() {
    TagMap.Ledger ledger = TagMap.ledger().set(KnownTags.HTTP_METHOD_OTEL_NAME, "GET");

    for (TagMap.EntryChangeReader change : ledger) {
      assertEquals(KnownTags.HTTP_METHOD_ID, change.tagId());
      assertEquals(KnownTags.HTTP_METHOD_NAME, change.tag());
    }
  }

  @Test
  void anUnknownIdIsRejected() {
    TagMap.Ledger ledger = TagMap.ledger();

    assertThrows(
        IllegalArgumentException.class, () -> ledger.set(KnownTagCodec.makeTagId(9999), "x"));
    assertThrows(
        IllegalArgumentException.class, () -> ledger.remove(KnownTagCodec.makeTagId(9999)));
  }

  @Test
  void theReaderReplaysSetsAndRemovalsInOrder() {
    TagMap.Ledger ledger =
        TagMap.ledger()
            .set(KnownTags.HTTP_ROUTE_ID, "/users")
            .set("my.custom.tag", 1)
            .remove(KnownTags.HTTP_ROUTE_ID)
            .remove("my.custom.tag");

    List<String> seen = new ArrayList<>();
    ledger.forEach(
        seen,
        (out, change) ->
            out.add(
                change.tag()
                    + (change.isRemoval() ? " removed" : "=" + change.objectValue())
                    + " #"
                    + Long.toHexString(change.tagId())));

    assertEquals(
        Arrays.asList(
            KnownTags.HTTP_ROUTE_NAME + "=/users #" + Long.toHexString(KnownTags.HTTP_ROUTE_ID),
            "my.custom.tag=1 #0",
            KnownTags.HTTP_ROUTE_NAME + " removed #" + Long.toHexString(KnownTags.HTTP_ROUTE_ID),
            "my.custom.tag removed #0"),
        seen);
    assertTrue(ledger.containsRemovals());
    assertTrue(ledger.build().isEmpty());
  }

  @Test
  void forEachReusesOneReader() {
    TagMap.Ledger ledger = TagMap.ledger().set(KnownTags.HTTP_ROUTE_ID, "/a").set("b", "c");
    List<TagMap.EntryChangeReader> readers = new ArrayList<>();

    ledger.forEach(readers, List::add);
    ledger.forEach(readers, List::add);

    assertEquals(4, readers.size());
    for (TagMap.EntryChangeReader reader : readers) {
      assertSame(readers.get(0), reader);
    }
  }

  @Test
  void aRemovalReadsAsNoEntry() {
    TagMap.Ledger ledger = TagMap.ledger().remove(KnownTags.HTTP_ROUTE_ID);

    for (TagMap.EntryChangeReader change : ledger) {
      assertTrue(change.isRemoval());
      assertNull(change.entry());
    }
  }

  @Test
  void smartRemoveOfATagNeverRecordedRecordsNothing() {
    TagMap.Ledger ledger = TagMap.ledger().set(KnownTags.HTTP_ROUTE_ID, "/users");

    ledger.smartRemove(KnownTags.HTTP_STATUS_CODE_ID);
    ledger.smartRemove(KnownTags.HTTP_STATUS_CODE_NAME);
    ledger.smartRemove("never.set");

    assertEquals(1, ledger.estimateSize());
    assertFalse(ledger.containsRemovals());
  }

  @Test
  void smartRemoveOfARecordedTagRecordsTheRemoval() {
    TagMap.Ledger ledger =
        TagMap.ledger()
            .set(KnownTags.HTTP_ROUTE_ID, "/users")
            .set(KnownTags.HTTP_METHOD_ID, "GET")
            .set("my.custom.tag", "x");

    ledger.smartRemove(KnownTags.HTTP_ROUTE_ID);
    ledger.smartRemove(KnownTags.HTTP_METHOD_OTEL_NAME); // by its OpenTelemetry name
    ledger.smartRemove("my.custom.tag");

    assertEquals(6, ledger.estimateSize());
    assertTrue(ledger.build().isEmpty());
  }

  @Test
  void smartRemoveOfACustomTagMatchesByName() {
    TagMap.Ledger ledger = TagMap.ledger().set("my.custom.tag", "x");

    ledger.smartRemove("my.other.tag");

    assertEquals(1, ledger.estimateSize());
  }

  @Test
  void hasInterceptedTagsTracksTheRecordedIds() {
    TagMap.Ledger ledger = TagMap.ledger().set(KnownTags.HTTP_ROUTE_ID, "/users").set("x", "y");
    assertFalse(ledger.hasInterceptedTags());

    ledger.set(KnownTags.SPAN_KIND_ID, "server");
    assertTrue(ledger.hasInterceptedTags());
  }

  @Test
  void resetClearsTheChangesAndTheSummaries() {
    TagMap.Ledger ledger =
        TagMap.ledger()
            .set(KnownTags.SPAN_KIND_ID, "server")
            .set("my.custom.tag", "x")
            .remove(KnownTags.HTTP_ROUTE_ID);

    ledger.reset();

    assertTrue(ledger.isDefinitelyEmpty());
    assertFalse(ledger.containsRemovals());
    assertFalse(ledger.hasInterceptedTags());
    ledger.smartRemove(KnownTags.SPAN_KIND_ID).smartRemove("my.custom.tag");
    assertTrue(ledger.isDefinitelyEmpty());
  }

  @Test
  void aLedgerOfKnownTagsNeverAllocatesItsNamesArray() {
    TagMap.Ledger ledger =
        TagMap.ledger(2)
            .set(KnownTags.HTTP_ROUTE_ID, "/a")
            .set(KnownTags.HTTP_METHOD_NAME, "GET")
            .set(KnownTags.HTTP_URL_ID, "http://x") // grows past 2
            .smartRemove("never.set");

    assertNull(ledger.tags);

    ledger.set("my.custom.tag", "x");
    assertEquals("x", ledger.build().getString("my.custom.tag"));
    assertEquals("/a", ledger.build().getString(KnownTags.HTTP_ROUTE_NAME));
  }

  @Test
  void theArraysGrowPastTheirInitialSize() {
    TagMap.Ledger ledger = TagMap.ledger(2);
    for (int i = 0; i < 20; ++i) {
      ledger.set("tag" + i, i);
    }

    TagMap map = ledger.build();
    assertEquals(20, map.size());
    assertEquals(19, map.getInt("tag19"));
  }
}
