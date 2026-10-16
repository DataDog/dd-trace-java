package datadog.trace.core;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.HashStore;
import datadog.trace.api.IdStore;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.TagRouting;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DirectionalTags.PendingAliases;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@link DirectionalTags}: a span's reading of direction-dependent names over its tags and its
 * pending aliases, with no span. The span-level behavior, with real spans, is in the directional
 * span tests.
 */
class DirectionalTagsTest {
  /** A span's stores, with no span: an id store and an own custom store. */
  static final class Stores {
    final IdStore ids;
    final HashStore custom;
    final boolean borrowed;

    Stores() {
      this(TagRouting.newIdStore(null), TagRouting.newCustomStore(null), false);
    }

    Stores(IdStore ids, HashStore custom, boolean borrowed) {
      this.ids = ids;
      this.custom = custom;
      this.borrowed = borrowed;
    }

    void set(String name, Object value) {
      long key = TagRouting.keyOf(name);
      if (TagRouting.isKnown(key)) {
        ids.set(key, value);
      } else {
        custom.set(name, value);
      }
    }

    Object getObject(String name) {
      TagMap.Entry entry = TagRouting.get(ids, custom, TagRouting.keyOf(name), name);
      return entry == null ? null : entry.objectValue();
    }

    int size() {
      return TagMap.viewOf(ids, custom, borrowed, null).size();
    }

    Object get(PendingAliases pending, String name, int direction) {
      return DirectionalTags.get(ids, custom, pending, name, direction);
    }

    Object get(PendingAliases pending, long tagId, int direction) {
      return DirectionalTags.get(ids, pending, tagId, direction);
    }

    boolean remove(PendingAliases pending, String name, int direction) {
      return DirectionalTags.remove(ids, custom, borrowed, pending, name, direction);
    }

    boolean remove(PendingAliases pending, long tagId, int direction) {
      return DirectionalTags.remove(ids, custom, borrowed, pending, tagId, direction);
    }

    long prepareAliasWrite(PendingAliases pending, String alias, int direction) {
      return DirectionalTags.prepareAliasWrite(pending, alias, direction);
    }

    boolean prepareWrite(PendingAliases pending, long tagId, int direction) {
      return DirectionalTags.prepareWrite(custom, borrowed, pending, tagId, direction);
    }

    PendingAliases resolvePending(PendingAliases pending, int direction) {
      return DirectionalTags.resolvePending(ids, borrowed ? null : custom, pending, direction);
    }
  }

  private static PendingAliases pending(String alias, Object value) {
    return DirectionalTags.addPending(null, alias, value);
  }

  @TableTest({
    "scenario                         | direction | alias                | tag              ",
    "client: alias reads its tag      | 1         | server.address       | peer.hostname    ",
    "server: alias reads its tag      | 0         | server.address       | http.hostname    ",
    "server: ip alias reads its tag   | 0         | network.peer.address | network.client.ip",
    "client: port alias reads its tag | 1         | server.port          | peer.port        ",
    "server: port alias reads its tag | 0         | client.port          | peer.port        "
  })
  void anAliasAndItsTagReadAsOne(int direction, String alias, String tag) {
    Stores tags = new Stores();
    tags.set(tag, "stored");

    assertEquals("stored", tags.get(null, alias, direction));
    assertEquals("stored", tags.get(null, tag, direction));
    assertEquals("stored", tags.get(null, KnownTagCodec.keyOf(tag), direction));

    // A pending alias is the newer write, so it wins on read, by name or by id.
    PendingAliases pending = pending(alias, "pending");
    assertEquals("pending", tags.get(pending, alias, direction));
    assertEquals("pending", tags.get(pending, tag, direction));
    assertEquals("pending", tags.get(pending, KnownTagCodec.keyOf(tag), direction));
  }

  @Test
  void withNoDirectionAnAliasReadsOnlyAsWritten() {
    Stores tags = new Stores();
    tags.set(Tags.PEER_HOSTNAME, "stored");

    assertNull(tags.get(null, "server.address", DIRECTION_UNKNOWN));
    assertEquals("stored", tags.get(null, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
    assertEquals("stored", tags.get(null, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN));

    PendingAliases pending = pending("server.address", "pending");
    assertEquals("pending", tags.get(pending, "server.address", DIRECTION_UNKNOWN));
    assertEquals("stored", tags.get(pending, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
  }

  @Test
  void aMissReadsAsNull() {
    Stores tags = new Stores();

    assertNull(tags.get(null, "my.tag", DIRECTION_OUTBOUND));
    assertNull(tags.get(null, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(tags.get(null, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
    assertNull(tags.get(null, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
    assertNull(tags.get(null, "server.address", DIRECTION_OUTBOUND));
    // client.port names no tag on a client span
    assertNull(tags.get(null, "client.port", DIRECTION_OUTBOUND));
  }

  @Test
  void otherNamesReadAsWritten() {
    Stores tags = new Stores();
    tags.set("my.tag", "custom");
    tags.set(Tags.HTTP_METHOD, "GET");

    assertEquals("custom", tags.get(null, "my.tag", DIRECTION_OUTBOUND));
    assertEquals("GET", tags.get(null, Tags.HTTP_METHOD, DIRECTION_OUTBOUND));
    assertEquals("GET", tags.get(null, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void anAliasWriteOnceTheDirectionIsKnownBecomesItsTag() {
    Stores tags = new Stores();
    PendingAliases pending = pending("server.address", "older");

    long tagId = tags.prepareAliasWrite(pending, "server.address", DIRECTION_OUTBOUND);

    assertEquals(KnownTags.PEER_HOSTNAME_ID, tagId);
    assertTrue(pending.isEmpty()); // the older pending value is dropped
  }

  @TableTest({
    "scenario                | name           | direction | result",
    "no direction yet        | server.address | -1        | -3    ",
    "no tag in the direction | client.port    | 1         | -3    ",
    "internal span           | server.address | 2         | -3    ",
    "not an alias            | my.tag         | 1         | 0     "
  })
  void anAliasWriteThatCannotResolveIsStoredAsWritten(String name, int direction, long result) {
    Stores tags = new Stores();
    PendingAliases pending = pending(name, "pending");

    // the sentinel (-3) marks a real alias left pending; 0 marks a name that is no alias at all
    assertEquals(result, tags.prepareAliasWrite(pending, name, direction));
    assertEquals("pending", pending.get(name));
  }

  @Test
  void aTagWriteWithNoDirectionDropsEveryAliasThatCouldDenoteIt() {
    Stores tags = new Stores();
    PendingAliases pending = pending("client.port", 1);
    pending.put("server.port", 2);

    tags.prepareWrite(pending, KnownTags.PEER_PORT_ID, DIRECTION_UNKNOWN);

    assertTrue(pending.isEmpty());
  }

  @Test
  void aTagWriteWithADirectionDropsOnlyItsAliasThere() {
    Stores tags = new Stores();
    PendingAliases pending = pending("client.port", 1); // no tag on a client span: kept
    pending.put("server.port", 2);

    tags.prepareWrite(pending, KnownTags.PEER_PORT_ID, DIRECTION_OUTBOUND);

    assertEquals(1, pending.get("client.port"));
    assertNull(pending.get("server.port"));
  }

  @Test
  void aTagWithoutAnAliasIsLeftAlone() {
    Stores tags = new Stores();
    PendingAliases pending = pending("server.address", "pending");

    tags.prepareWrite(pending, KnownTags.HTTP_METHOD_ID, DIRECTION_UNKNOWN);
    tags.prepareWrite(pending, KnownTags.PEER_HOSTNAME_ID, DIRECTION_NONE);

    assertEquals("pending", pending.get("server.address"));
  }

  @Test
  void removingAnAliasRemovesItsTag() {
    Stores tags = new Stores();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    PendingAliases pending = pending("server.address", "pending");

    tags.remove(pending, "server.address", DIRECTION_OUTBOUND);

    assertNull(tags.getObject(Tags.PEER_HOSTNAME));
    assertTrue(pending.isEmpty());
  }

  @Test
  void removingATagRemovesItsAlias() {
    Stores byName = new Stores();
    byName.set(Tags.HTTP_HOSTNAME, "stored");
    PendingAliases pendingByName = pending("server.address", "pending");
    byName.remove(pendingByName, Tags.HTTP_HOSTNAME, DIRECTION_INBOUND);
    assertNull(byName.getObject(Tags.HTTP_HOSTNAME));
    assertTrue(pendingByName.isEmpty());

    Stores byId = new Stores();
    byId.set(Tags.HTTP_HOSTNAME, "stored");
    PendingAliases pendingById = pending("server.address", "pending");
    byId.remove(pendingById, KnownTags.HTTP_HOSTNAME_ID, DIRECTION_INBOUND);
    assertNull(byId.getObject(Tags.HTTP_HOSTNAME));
    assertTrue(pendingById.isEmpty());
  }

  @Test
  void removingAnAliasAlsoRemovesACopyEmittedEarlier() {
    Stores tags = new Stores();
    PendingAliases pending = pending("server.address", "pending");
    DirectionalTags.copyPending(pending, tags.custom);
    assertEquals("pending", tags.getObject("server.address"));

    tags.remove(pending, "server.address", DIRECTION_UNKNOWN);

    assertNull(tags.getObject("server.address"));
    assertTrue(pending.isEmpty());
  }

  @Test
  void removingATagWithNoAliasLeavesUnrelatedTags() {
    Stores tags = new Stores();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    tags.set("my.tag", "custom");

    tags.remove(null, "my.tag", DIRECTION_OUTBOUND);
    tags.remove(null, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND);

    assertEquals("stored", tags.getObject(Tags.PEER_HOSTNAME));
    assertNull(tags.getObject("my.tag"));
  }

  @TableTest({
    "scenario         | direction | alias                | tag              ",
    "client hostname  | 1         | server.address       | peer.hostname    ",
    "server hostname  | 0         | server.address       | http.hostname    ",
    "server client ip | 0         | network.peer.address | network.client.ip",
    "client port      | 1         | server.port          | peer.port        "
  })
  void resolvingFoldsAPendingAliasIntoItsTag(int direction, String alias, String tag) {
    Stores tags = new Stores();
    tags.set(tag, "older");
    tags.set("my.tag", "custom");

    assertNull(tags.resolvePending(pending(alias, "newer"), direction));

    assertEquals("newer", tags.getObject(tag));
    assertNull(tags.getObject(alias));
    assertEquals("custom", tags.getObject("my.tag"));
  }

  @Test
  void resolvingLeavesAnAliasWithNoTagPending() {
    Stores tags = new Stores();
    PendingAliases pending = pending("client.port", 1234);
    pending.put("server.address", "pending");

    assertSame(pending, tags.resolvePending(pending, DIRECTION_OUTBOUND));
    assertEquals(1234, pending.get("client.port"));
    assertNull(pending.get("server.address"));
    assertEquals("pending", tags.getObject(Tags.PEER_HOSTNAME));

    PendingAliases unknown = pending("server.address", "pending");
    assertSame(unknown, tags.resolvePending(unknown, DIRECTION_UNKNOWN));
    assertEquals("pending", unknown.get("server.address"));
  }

  @Test
  void anAliasThatNeverResolvesIsEmittedUnderItsLiteralName() {
    Stores tags = new Stores();
    PendingAliases pending = pending("server.address", "pending");

    DirectionalTags.copyPending(pending, tags.custom);

    assertEquals("pending", tags.getObject("server.address"));
    assertEquals(1, tags.size());
  }

  @Test
  void removingAnAliasBeforeTheDirectionIsKnownRemovesEveryTagItCouldDenote() {
    Stores tags = new Stores();
    tags.set(Tags.PEER_HOSTNAME, "older");

    tags.remove(null, "server.address", DIRECTION_UNKNOWN);

    // once the span is a client, server.address names peer.hostname: the removal must stick
    assertNull(tags.get(null, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(tags.get(null, "server.address", DIRECTION_OUTBOUND));
  }

  @Test
  void removingATagBeforeTheDirectionIsKnownRemovesThePendingAliasesThatCouldDenoteIt() {
    Stores byName = new Stores();
    PendingAliases pendingByName = pending("server.address", "pending");
    byName.remove(pendingByName, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN);
    assertTrue(pendingByName.isEmpty());
    assertNull(byName.get(pendingByName, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));

    Stores byId = new Stores();
    PendingAliases pendingById = pending("server.address", "pending");
    byId.remove(pendingById, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN);
    assertTrue(pendingById.isEmpty());
    assertNull(byId.get(pendingById, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void removingACustomTagBeforeTheDirectionIsKnownLeavesOtherTags() {
    Stores tags = new Stores();
    tags.set(Tags.PEER_HOSTNAME, "kept");
    tags.set("my.tag", "gone");

    tags.remove(null, "my.tag", DIRECTION_UNKNOWN);

    assertNull(tags.getObject("my.tag"));
    assertEquals("kept", tags.get(null, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
  }

  @Test
  void thePendingHolderGrowsAndKeepsWriteOrder() {
    PendingAliases pending = pending("a", 1);
    pending.put("b", 2);
    pending.put("c", 3);
    pending.put("a", 4); // replaces in place
    pending.remove("b");
    pending.remove("missing");

    assertEquals(4, pending.get("a"));
    assertNull(pending.get("b"));
    assertEquals(3, pending.get("c"));
  }

  @Test
  void removingATagInABorrowedStoreAsksForAStoreOfTheSpansOwn() {
    TagMap parent = TagMap.create();
    parent.set("inherited", "p");
    parent.set("server.address", "inherited-alias");
    TagMap frozen = TagRouting.readThroughParent(parent.freeze());
    HashStore borrowed = TagRouting.inheritedCustomStore(frozen);
    Stores tags = new Stores(TagRouting.newIdStore(frozen), borrowed, true);

    assertFalse(tags.remove(null, "not.there", DIRECTION_OUTBOUND)); // nothing to hide
    assertTrue(tags.remove(null, "inherited", DIRECTION_OUTBOUND));
    assertEquals("p", borrowed.get("inherited").objectValue()); // the borrowed store is untouched
    assertTrue(tags.prepareWrite(null, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
    assertTrue(tags.remove(null, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
    assertTrue(tags.remove(null, "server.address", DIRECTION_UNKNOWN));

    // repeated on a store of the span's own, the removal hides the inherited tag
    Stores own = new Stores(tags.ids, TagRouting.newCustomStore(borrowed), false);
    assertFalse(own.remove(null, "inherited", DIRECTION_OUTBOUND));
    assertNull(own.getObject("inherited"));
  }
}
