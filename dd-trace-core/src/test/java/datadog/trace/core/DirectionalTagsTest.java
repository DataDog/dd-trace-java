package datadog.trace.core;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@link DirectionalTags}: a span's reading of direction-dependent names over a plain {@link
 * TagMap}. The span-level behavior, with real spans, is in the directional span tests.
 */
class DirectionalTagsTest {
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

    assertEquals("stored", DirectionalTags.get(tags, alias, direction));
    assertEquals("stored", DirectionalTags.get(tags, tag, direction));
    assertEquals("stored", DirectionalTags.get(tags, KnownTagCodec.keyOf(tag), direction));

    // A pending alias is the newer write, so it wins on read, by name or by id.
    tags.set(alias, "pending");
    assertEquals("pending", DirectionalTags.get(tags, alias, direction));
    assertEquals("pending", DirectionalTags.get(tags, tag, direction));
    assertEquals("pending", DirectionalTags.get(tags, KnownTagCodec.keyOf(tag), direction));
  }

  @Test
  void withNoDirectionAnAliasReadsOnlyAsWritten() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");

    assertNull(DirectionalTags.get(tags, "server.address", DIRECTION_UNKNOWN));
    assertEquals("stored", DirectionalTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
    assertEquals(
        "stored", DirectionalTags.get(tags, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN));

    tags.set("server.address", "pending");
    assertEquals("pending", DirectionalTags.get(tags, "server.address", DIRECTION_UNKNOWN));
    assertEquals("stored", DirectionalTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
  }

  @Test
  void aMissReadsAsNull() {
    TagMap tags = TagMap.create();

    assertNull(DirectionalTags.get(tags, "my.tag", DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(tags, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(tags, "server.address", DIRECTION_OUTBOUND));
    // client.port names no tag on a client span
    assertNull(DirectionalTags.get(tags, "client.port", DIRECTION_OUTBOUND));
  }

  @Test
  void otherNamesReadAsWritten() {
    TagMap tags = TagMap.create();
    tags.set("my.tag", "custom");
    tags.set(Tags.HTTP_METHOD, "GET");

    assertEquals("custom", DirectionalTags.get(tags, "my.tag", DIRECTION_OUTBOUND));
    assertEquals("GET", DirectionalTags.get(tags, Tags.HTTP_METHOD, DIRECTION_OUTBOUND));
    assertEquals("GET", DirectionalTags.get(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void anAliasWriteOnceTheDirectionIsKnownBecomesItsTag() {
    TagMap tags = TagMap.create();
    tags.set("server.address", "older");

    long tagId = DirectionalTags.prepareAliasWrite(tags, "server.address", DIRECTION_OUTBOUND);

    assertEquals(KnownTags.PEER_HOSTNAME_ID, tagId);
    assertNull(tags.getObject("server.address")); // the older pending value is dropped
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
    tags.set(name, "pending");

    // the sentinel (-3) marks a real alias left pending; 0 marks a name that is no alias at all
    assertEquals(result, DirectionalTags.prepareAliasWrite(tags, name, direction));
    assertEquals("pending", tags.getObject(name));
  }

  @Test
  void aTagWriteWithNoDirectionDropsEveryAliasThatCouldDenoteIt() {
    TagMap tags = TagMap.create();
    tags.set("client.port", 1);
    tags.set("server.port", 2);

    DirectionalTags.prepareWrite(tags, KnownTags.PEER_PORT_ID, DIRECTION_UNKNOWN);

    assertNull(tags.getObject("client.port"));
    assertNull(tags.getObject("server.port"));
  }

  @Test
  void aTagWriteWithADirectionDropsOnlyItsAliasThere() {
    TagMap tags = TagMap.create();
    tags.set("client.port", 1); // no tag on a client span: a custom tag, kept
    tags.set("server.port", 2);

    DirectionalTags.prepareWrite(tags, KnownTags.PEER_PORT_ID, DIRECTION_OUTBOUND);

    assertEquals(1, tags.getObject("client.port"));
    assertNull(tags.getObject("server.port"));
  }

  @Test
  void aTagWithoutAnAliasIsLeftAlone() {
    TagMap tags = TagMap.create();
    tags.set("server.address", "pending");

    DirectionalTags.prepareWrite(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_UNKNOWN);
    DirectionalTags.prepareWrite(tags, KnownTags.PEER_HOSTNAME_ID, DIRECTION_NONE);

    assertEquals("pending", tags.getObject("server.address"));
  }

  @Test
  void removingAnAliasRemovesItsTag() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    tags.set("server.address", "pending");

    DirectionalTags.remove(tags, "server.address", DIRECTION_OUTBOUND);

    assertNull(tags.getObject(Tags.PEER_HOSTNAME));
    assertNull(tags.getObject("server.address"));
  }

  @Test
  void removingATagRemovesItsAlias() {
    TagMap byName = TagMap.create();
    byName.set(Tags.HTTP_HOSTNAME, "stored");
    byName.set("server.address", "pending");
    DirectionalTags.remove(byName, Tags.HTTP_HOSTNAME, DIRECTION_INBOUND);
    assertNull(byName.getObject(Tags.HTTP_HOSTNAME));
    assertNull(byName.getObject("server.address"));

    TagMap byId = TagMap.create();
    byId.set(Tags.HTTP_HOSTNAME, "stored");
    byId.set("server.address", "pending");
    DirectionalTags.remove(byId, KnownTags.HTTP_HOSTNAME_ID, DIRECTION_INBOUND);
    assertNull(byId.getObject(Tags.HTTP_HOSTNAME));
    assertNull(byId.getObject("server.address"));
  }

  @Test
  void removingATagWithNoAliasLeavesUnrelatedTags() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    tags.set("my.tag", "custom");

    DirectionalTags.remove(tags, "my.tag", DIRECTION_OUTBOUND);
    DirectionalTags.remove(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND);

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
    tags.set(alias, "newer");
    tags.set("my.tag", "custom");

    DirectionalTags.resolveScopedNames(tags, direction);

    assertEquals("newer", tags.getObject(tag));
    assertNull(tags.getObject(alias));
    assertEquals("custom", tags.getObject("my.tag"));
  }

  @Test
  void resolvingLeavesAnAliasWithNoTagAsWritten() {
    TagMap tags = TagMap.create();
    tags.set("client.port", 1234);
    tags.set("server.address", "pending");

    DirectionalTags.resolveScopedNames(tags, DIRECTION_OUTBOUND);
    assertEquals(1234, tags.getObject("client.port"));
    assertEquals("pending", tags.getObject(Tags.PEER_HOSTNAME));

    TagMap unknown = TagMap.create();
    unknown.set("server.address", "pending");
    DirectionalTags.resolveScopedNames(unknown, DIRECTION_UNKNOWN);
    assertEquals("pending", unknown.getObject("server.address"));
  }

  @Test
  void resolvingWithNoAliasChangesNothing() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");

    DirectionalTags.resolveScopedNames(tags, DIRECTION_OUTBOUND);

    assertEquals("stored", tags.getObject(Tags.PEER_HOSTNAME));
    assertEquals(1, tags.size());
  }

  @Test
  void removingAnAliasBeforeTheDirectionIsKnownRemovesEveryTagItCouldDenote() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "older");

    DirectionalTags.remove(tags, "server.address", DIRECTION_UNKNOWN);

    // once the span is a client, server.address names peer.hostname: the removal must stick
    assertNull(DirectionalTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(DirectionalTags.get(tags, "server.address", DIRECTION_OUTBOUND));
  }

  @Test
  void removingATagBeforeTheDirectionIsKnownRemovesThePendingAliasesThatCouldDenoteIt() {
    TagMap byName = TagMap.create();
    byName.set("server.address", "pending");
    DirectionalTags.remove(byName, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN);
    assertNull(DirectionalTags.get(byName, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));

    TagMap byId = TagMap.create();
    byId.set("server.address", "pending");
    DirectionalTags.remove(byId, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN);
    assertNull(DirectionalTags.get(byId, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void removingACustomTagBeforeTheDirectionIsKnownLeavesOtherTags() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "kept");
    tags.set("my.tag", "gone");

    DirectionalTags.remove(tags, "my.tag", DIRECTION_UNKNOWN);

    assertNull(tags.getObject("my.tag"));
    assertEquals("kept", DirectionalTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
  }
}
