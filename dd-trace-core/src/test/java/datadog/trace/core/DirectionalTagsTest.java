package datadog.trace.core;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
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
  /** The stores DirectionalTags works on, over a plain TagMap in place of a span. */
  private static DirectionalTags.TagStores stores(TagMap tags) {
    return new DirectionalTags.TagStores() {
      @Override
      public Object knownValue(long tagId) {
        return tags.getObject(KnownTagCodec.nameOf(tagId));
      }

      @Override
      public void setKnown(long tagId, Object value) {
        tags.set(tagId, value);
      }

      @Override
      public void removeKnown(long tagId) {
        tags.getAndRemove(tagId);
      }

      @Override
      public Object customValue(String name) {
        return tags.getObject(name);
      }

      @Override
      public void setCustom(String name, Object value) {
        tags.set(name, value);
      }

      @Override
      public void removeCustom(String name) {
        tags.remove(name);
      }
    };
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
    TagMap tags = TagMap.create();
    tags.set(tag, "stored");

    assertEquals("stored", DirectionalTags.get(stores(tags), null, alias, direction));
    assertEquals("stored", DirectionalTags.get(stores(tags), null, tag, direction));
    assertEquals(
        "stored", DirectionalTags.get(stores(tags), null, KnownTagCodec.keyOf(tag), direction));

    // A pending alias is the newer write, so it wins on read, by name or by id.
    PendingAliases pending = pending(alias, "pending");
    assertEquals("pending", DirectionalTags.get(stores(tags), pending, alias, direction));
    assertEquals("pending", DirectionalTags.get(stores(tags), pending, tag, direction));
    assertEquals(
        "pending", DirectionalTags.get(stores(tags), pending, KnownTagCodec.keyOf(tag), direction));
  }

  @Test
  void withNoDirectionAnAliasReadsOnlyAsWritten() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");

    assertNull(DirectionalTags.get(stores(tags), null, "server.address", DIRECTION_UNKNOWN));
    assertEquals(
        "stored", DirectionalTags.get(stores(tags), null, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
    assertEquals(
        "stored",
        DirectionalTags.get(stores(tags), null, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN));

    PendingAliases pending = pending("server.address", "pending");
    assertEquals(
        "pending", DirectionalTags.get(stores(tags), pending, "server.address", DIRECTION_UNKNOWN));
    assertEquals(
        "stored",
        DirectionalTags.get(stores(tags), pending, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
  }

  @Test
  void aMissReadsAsNull() {
    TagMap tags = TagMap.create();

    assertNull(DirectionalTags.get(stores(tags), null, "my.tag", DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(stores(tags), null, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(
        DirectionalTags.get(stores(tags), null, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
    assertNull(
        DirectionalTags.get(stores(tags), null, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(stores(tags), null, "server.address", DIRECTION_OUTBOUND));
    // client.port names no tag on a client span
    assertNull(DirectionalTags.get(stores(tags), null, "client.port", DIRECTION_OUTBOUND));
  }

  @Test
  void otherNamesReadAsWritten() {
    TagMap tags = TagMap.create();
    tags.set("my.tag", "custom");
    tags.set(Tags.HTTP_METHOD, "GET");

    assertEquals("custom", DirectionalTags.get(stores(tags), null, "my.tag", DIRECTION_OUTBOUND));
    assertEquals(
        "GET", DirectionalTags.get(stores(tags), null, Tags.HTTP_METHOD, DIRECTION_OUTBOUND));
    assertEquals(
        "GET",
        DirectionalTags.get(stores(tags), null, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void anAliasWriteOnceTheDirectionIsKnownBecomesItsTag() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("server.address", "older");

    long tagId =
        DirectionalTags.prepareAliasWrite(
            stores(tags), pending, "server.address", DIRECTION_OUTBOUND);

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
    TagMap tags = TagMap.create();
    PendingAliases pending = pending(name, "pending");

    // the sentinel (-3) marks a real alias left pending; 0 marks a name that is no alias at all
    assertEquals(result, DirectionalTags.prepareAliasWrite(stores(tags), pending, name, direction));
    assertEquals("pending", pending.get(name));
  }

  @Test
  void aTagWriteWithNoDirectionDropsEveryAliasThatCouldDenoteIt() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("client.port", 1);
    pending.put("server.port", 2);

    DirectionalTags.prepareWrite(stores(tags), pending, KnownTags.PEER_PORT_ID, DIRECTION_UNKNOWN);

    assertTrue(pending.isEmpty());
  }

  @Test
  void aTagWriteWithADirectionDropsOnlyItsAliasThere() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("client.port", 1); // no tag on a client span: kept
    pending.put("server.port", 2);

    DirectionalTags.prepareWrite(stores(tags), pending, KnownTags.PEER_PORT_ID, DIRECTION_OUTBOUND);

    assertEquals(1, pending.get("client.port"));
    assertNull(pending.get("server.port"));
  }

  @Test
  void aTagWithoutAnAliasIsLeftAlone() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("server.address", "pending");

    DirectionalTags.prepareWrite(
        stores(tags), pending, KnownTags.HTTP_METHOD_ID, DIRECTION_UNKNOWN);
    DirectionalTags.prepareWrite(stores(tags), pending, KnownTags.PEER_HOSTNAME_ID, DIRECTION_NONE);

    assertEquals("pending", pending.get("server.address"));
  }

  @Test
  void removingAnAliasRemovesItsTag() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    PendingAliases pending = pending("server.address", "pending");

    DirectionalTags.remove(stores(tags), pending, "server.address", DIRECTION_OUTBOUND);

    assertNull(tags.getObject(Tags.PEER_HOSTNAME));
    assertTrue(pending.isEmpty());
  }

  @Test
  void removingATagRemovesItsAlias() {
    TagMap byName = TagMap.create();
    byName.set(Tags.HTTP_HOSTNAME, "stored");
    PendingAliases pendingByName = pending("server.address", "pending");
    DirectionalTags.remove(stores(byName), pendingByName, Tags.HTTP_HOSTNAME, DIRECTION_INBOUND);
    assertNull(byName.getObject(Tags.HTTP_HOSTNAME));
    assertTrue(pendingByName.isEmpty());

    TagMap byId = TagMap.create();
    byId.set(Tags.HTTP_HOSTNAME, "stored");
    PendingAliases pendingById = pending("server.address", "pending");
    DirectionalTags.remove(
        stores(byId), pendingById, KnownTags.HTTP_HOSTNAME_ID, DIRECTION_INBOUND);
    assertNull(byId.getObject(Tags.HTTP_HOSTNAME));
    assertTrue(pendingById.isEmpty());
  }

  @Test
  void removingAnAliasAlsoRemovesACopyEmittedEarlier() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("server.address", "pending");
    DirectionalTags.copyPending(pending, stores(tags));
    assertEquals("pending", tags.getObject("server.address"));

    DirectionalTags.remove(stores(tags), pending, "server.address", DIRECTION_UNKNOWN);

    assertNull(tags.getObject("server.address"));
    assertTrue(pending.isEmpty());
  }

  @Test
  void removingATagWithNoAliasLeavesUnrelatedTags() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    tags.set("my.tag", "custom");

    DirectionalTags.remove(stores(tags), null, "my.tag", DIRECTION_OUTBOUND);
    DirectionalTags.remove(stores(tags), null, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND);

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
    TagMap tags = TagMap.create();
    tags.set(tag, "older");
    tags.set("my.tag", "custom");

    assertNull(DirectionalTags.resolvePending(stores(tags), pending(alias, "newer"), direction));

    assertEquals("newer", tags.getObject(tag));
    assertNull(tags.getObject(alias));
    assertEquals("custom", tags.getObject("my.tag"));
  }

  @Test
  void resolvingLeavesAnAliasWithNoTagPending() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("client.port", 1234);
    pending.put("server.address", "pending");

    assertSame(pending, DirectionalTags.resolvePending(stores(tags), pending, DIRECTION_OUTBOUND));
    assertEquals(1234, pending.get("client.port"));
    assertNull(pending.get("server.address"));
    assertEquals("pending", tags.getObject(Tags.PEER_HOSTNAME));

    PendingAliases unknown = pending("server.address", "pending");
    assertSame(unknown, DirectionalTags.resolvePending(stores(tags), unknown, DIRECTION_UNKNOWN));
    assertEquals("pending", unknown.get("server.address"));
  }

  @Test
  void anAliasThatNeverResolvesIsEmittedUnderItsLiteralName() {
    TagMap tags = TagMap.create();
    PendingAliases pending = pending("server.address", "pending");

    DirectionalTags.copyPending(pending, stores(tags));

    assertEquals("pending", tags.getObject("server.address"));
    assertEquals(1, tags.size());
  }

  @Test
  void removingAnAliasBeforeTheDirectionIsKnownRemovesEveryTagItCouldDenote() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "older");

    DirectionalTags.remove(stores(tags), null, "server.address", DIRECTION_UNKNOWN);

    // once the span is a client, server.address names peer.hostname: the removal must stick
    assertNull(DirectionalTags.get(stores(tags), null, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(stores(tags), null, "server.address", DIRECTION_OUTBOUND));
  }

  @Test
  void removingATagBeforeTheDirectionIsKnownRemovesThePendingAliasesThatCouldDenoteIt() {
    TagMap byName = TagMap.create();
    PendingAliases pendingByName = pending("server.address", "pending");
    DirectionalTags.remove(stores(byName), pendingByName, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN);
    assertTrue(pendingByName.isEmpty());
    assertNull(
        DirectionalTags.get(stores(byName), pendingByName, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));

    TagMap byId = TagMap.create();
    PendingAliases pendingById = pending("server.address", "pending");
    DirectionalTags.remove(
        stores(byId), pendingById, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN);
    assertTrue(pendingById.isEmpty());
    assertNull(
        DirectionalTags.get(
            stores(byId), pendingById, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void removingACustomTagBeforeTheDirectionIsKnownLeavesOtherTags() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "kept");
    tags.set("my.tag", "gone");

    DirectionalTags.remove(stores(tags), null, "my.tag", DIRECTION_UNKNOWN);

    assertNull(tags.getObject("my.tag"));
    assertEquals(
        "kept", DirectionalTags.get(stores(tags), null, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
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
}
