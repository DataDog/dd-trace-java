package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_NONE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@link SpanTags}: a span's reading of direction-dependent names over a plain {@link TagMap}. The
 * span-level behavior, with real spans, is in dd-trace-core's directional span tests.
 */
class SpanTagsTest {
  @TableTest({
    "scenario | kind     | direction",
    "server   | server   | 0        ",
    "consumer | consumer | 0        ",
    "client   | client   | 1        ",
    "producer | producer | 1        ",
    "internal | internal | 2        ",
    "broker   | broker   | -1       ",
    "custom   | custom   | -1       "
  })
  void theDirectionComesFromTheSpanKind(String kind, int direction) {
    assertEquals(direction, SpanTags.directionOfKind(kind));

    TagMap tags = TagMap.create();
    tags.set(Tags.SPAN_KIND, kind);
    assertEquals(direction, SpanTags.directionOf(tags));
  }

  @Test
  void noKindIsNoDirection() {
    assertEquals(DIRECTION_UNKNOWN, SpanTags.directionOfKind(null));
    assertEquals(DIRECTION_UNKNOWN, SpanTags.directionOf(TagMap.create()));
  }

  @TableTest({
    "scenario                   | tag           | direction | otelName           ",
    "peer.port, client          | peer.port     | 1         | server.port        ",
    "peer.port, server          | peer.port     | 0         | client.port        ",
    "peer.port, internal        | peer.port     | 2         | peer.port          ",
    "peer.port, no direction    | peer.port     | -1        | peer.port          ",
    "peer.hostname, client      | peer.hostname | 1         | server.address     ",
    "peer.hostname, server      | peer.hostname | 0         | peer.hostname      ",
    "renamed in every direction | http.method   | -1        | http.request.method",
    "custom tag                 | my.tag        | 1         | my.tag             "
  })
  void anEntryIsNamedForTheDirection(String tag, int direction, String otelName) {
    TagMap tags = TagMap.create();
    tags.set(tag, "value");

    assertEquals(otelName, SpanTags.openTelemetryTagOf(tags.getEntry(tag), direction));
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

    assertEquals("stored", SpanTags.get(tags, alias, direction));
    assertEquals("stored", SpanTags.get(tags, tag, direction));
    assertEquals("stored", SpanTags.get(tags, KnownTagCodec.keyOf(tag), direction));

    // A pending alias is the newer write, so it wins on read, by name or by id.
    tags.set(alias, "pending");
    assertEquals("pending", SpanTags.get(tags, alias, direction));
    assertEquals("pending", SpanTags.get(tags, tag, direction));
    assertEquals("pending", SpanTags.get(tags, KnownTagCodec.keyOf(tag), direction));
  }

  @Test
  void withNoDirectionAnAliasReadsOnlyAsWritten() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");

    assertNull(SpanTags.get(tags, "server.address", DIRECTION_UNKNOWN));
    assertEquals("stored", SpanTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
    assertEquals("stored", SpanTags.get(tags, KnownTags.PEER_HOSTNAME_ID, DIRECTION_UNKNOWN));

    tags.set("server.address", "pending");
    assertEquals("pending", SpanTags.get(tags, "server.address", DIRECTION_UNKNOWN));
    assertEquals("stored", SpanTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_UNKNOWN));
  }

  @Test
  void aMissReadsAsNull() {
    TagMap tags = TagMap.create();

    assertNull(SpanTags.get(tags, "my.tag", DIRECTION_OUTBOUND));
    assertNull(SpanTags.get(tags, Tags.PEER_HOSTNAME, DIRECTION_OUTBOUND));
    assertNull(SpanTags.get(tags, KnownTags.PEER_HOSTNAME_ID, DIRECTION_OUTBOUND));
    assertNull(SpanTags.get(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
    assertNull(SpanTags.get(tags, "server.address", DIRECTION_OUTBOUND));
    // client.port names no tag on a client span
    assertNull(SpanTags.get(tags, "client.port", DIRECTION_OUTBOUND));
  }

  @Test
  void otherNamesReadAsWritten() {
    TagMap tags = TagMap.create();
    tags.set("my.tag", "custom");
    tags.set(Tags.HTTP_METHOD, "GET");

    assertEquals("custom", SpanTags.get(tags, "my.tag", DIRECTION_OUTBOUND));
    assertEquals("GET", SpanTags.get(tags, Tags.HTTP_METHOD, DIRECTION_OUTBOUND));
    assertEquals("GET", SpanTags.get(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND));
  }

  @Test
  void anAliasWriteOnceTheDirectionIsKnownBecomesItsTag() {
    TagMap tags = TagMap.create();
    tags.set("server.address", "older");

    long tagId = SpanTags.prepareAliasWrite(tags, "server.address", DIRECTION_OUTBOUND);

    assertEquals(KnownTags.PEER_HOSTNAME_ID, tagId);
    assertNull(tags.getObject("server.address")); // the older pending value is dropped
  }

  @TableTest({
    "scenario                | name           | direction",
    "no direction yet        | server.address | -1       ",
    "no tag in the direction | client.port    | 1        ",
    "internal span           | server.address | 2        ",
    "not an alias            | my.tag         | 1        "
  })
  void anAliasWriteThatCannotResolveIsStoredAsWritten(String name, int direction) {
    TagMap tags = TagMap.create();
    tags.set(name, "pending");

    assertEquals(0L, SpanTags.prepareAliasWrite(tags, name, direction));
    assertEquals("pending", tags.getObject(name));
  }

  @Test
  void aTagWriteWithNoDirectionDropsEveryAliasThatCouldDenoteIt() {
    TagMap tags = TagMap.create();
    tags.set("client.port", 1);
    tags.set("server.port", 2);

    SpanTags.prepareWrite(tags, KnownTags.PEER_PORT_ID, DIRECTION_UNKNOWN);

    assertNull(tags.getObject("client.port"));
    assertNull(tags.getObject("server.port"));
  }

  @Test
  void aTagWriteWithADirectionDropsOnlyItsAliasThere() {
    TagMap tags = TagMap.create();
    tags.set("client.port", 1); // no tag on a client span: a custom tag, kept
    tags.set("server.port", 2);

    SpanTags.prepareWrite(tags, KnownTags.PEER_PORT_ID, DIRECTION_OUTBOUND);

    assertEquals(1, tags.getObject("client.port"));
    assertNull(tags.getObject("server.port"));
  }

  @Test
  void aTagWithoutAnAliasIsLeftAlone() {
    TagMap tags = TagMap.create();
    tags.set("server.address", "pending");

    SpanTags.prepareWrite(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_UNKNOWN);
    SpanTags.prepareWrite(tags, KnownTags.PEER_HOSTNAME_ID, DIRECTION_NONE);

    assertEquals("pending", tags.getObject("server.address"));
  }

  @Test
  void entriesThatDependOnDirectionAreRecognized() {
    TagMap tags = TagMap.create();
    tags.set("server.address", "a");
    tags.set(Tags.PEER_HOSTNAME, "b");
    tags.set(Tags.HTTP_METHOD, "c");
    tags.set("my.tag", "d");

    assertTrue(SpanTags.isDirectionDependent(tags.getEntry("server.address")));
    assertTrue(SpanTags.isDirectionDependent(tags.getEntry(Tags.PEER_HOSTNAME)));
    assertFalse(SpanTags.isDirectionDependent(tags.getEntry(Tags.HTTP_METHOD)));
    assertFalse(SpanTags.isDirectionDependent(tags.getEntry("my.tag")));
  }

  @Test
  void removingAnAliasRemovesItsTag() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    tags.set("server.address", "pending");

    SpanTags.remove(tags, "server.address", DIRECTION_OUTBOUND);

    assertNull(tags.getObject(Tags.PEER_HOSTNAME));
    assertNull(tags.getObject("server.address"));
  }

  @Test
  void removingATagRemovesItsAlias() {
    TagMap byName = TagMap.create();
    byName.set(Tags.HTTP_HOSTNAME, "stored");
    byName.set("server.address", "pending");
    SpanTags.remove(byName, Tags.HTTP_HOSTNAME, DIRECTION_INBOUND);
    assertNull(byName.getObject(Tags.HTTP_HOSTNAME));
    assertNull(byName.getObject("server.address"));

    TagMap byId = TagMap.create();
    byId.set(Tags.HTTP_HOSTNAME, "stored");
    byId.set("server.address", "pending");
    SpanTags.remove(byId, KnownTags.HTTP_HOSTNAME_ID, DIRECTION_INBOUND);
    assertNull(byId.getObject(Tags.HTTP_HOSTNAME));
    assertNull(byId.getObject("server.address"));
  }

  @Test
  void removingWithNoDirectionRemovesOnlyTheName() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");
    tags.set("server.address", "pending");
    tags.set("my.tag", "custom");

    SpanTags.remove(tags, "server.address", DIRECTION_UNKNOWN);
    SpanTags.remove(tags, "my.tag", DIRECTION_OUTBOUND);
    SpanTags.remove(tags, KnownTags.HTTP_METHOD_ID, DIRECTION_OUTBOUND);

    assertEquals("stored", tags.getObject(Tags.PEER_HOSTNAME));
    assertNull(tags.getObject("server.address"));
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

    SpanTags.resolveScopedNames(tags, direction);

    assertEquals("newer", tags.getObject(tag));
    assertNull(tags.getObject(alias));
    assertEquals("custom", tags.getObject("my.tag"));
  }

  @Test
  void resolvingLeavesAnAliasWithNoTagAsWritten() {
    TagMap tags = TagMap.create();
    tags.set("client.port", 1234);
    tags.set("server.address", "pending");

    SpanTags.resolveScopedNames(tags, DIRECTION_OUTBOUND);
    assertEquals(1234, tags.getObject("client.port"));
    assertEquals("pending", tags.getObject(Tags.PEER_HOSTNAME));

    TagMap unknown = TagMap.create();
    unknown.set("server.address", "pending");
    SpanTags.resolveScopedNames(unknown, DIRECTION_UNKNOWN);
    assertEquals("pending", unknown.getObject("server.address"));
  }

  @Test
  void resolvingWithNoAliasChangesNothing() {
    TagMap tags = TagMap.create();
    tags.set(Tags.PEER_HOSTNAME, "stored");

    SpanTags.resolveScopedNames(tags, DIRECTION_OUTBOUND);

    assertEquals("stored", tags.getObject(Tags.PEER_HOSTNAME));
    assertEquals(1, tags.size());
  }
}
