package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@link KnownTagCodec}'s direction helpers: a span's direction, and the direction-dependent names.
 */
class KnownTagCodecDirectionTest {
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
    assertEquals(direction, KnownTagCodec.directionOfKind(kind));

    TagMap tags = TagMap.create();
    tags.set(Tags.SPAN_KIND, kind);
    assertEquals(direction, KnownTagCodec.directionOf(tags));
  }

  @Test
  void noKindIsNoDirection() {
    assertEquals(DIRECTION_UNKNOWN, KnownTagCodec.directionOfKind(null));
    assertEquals(DIRECTION_UNKNOWN, KnownTagCodec.directionOf(TagMap.create()));
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

    assertEquals(otelName, tags.getEntry(tag).openTelemetryTag(direction));
  }

  @Test
  void entriesThatDependOnDirectionAreRecognized() {
    TagMap tags = TagMap.create();
    tags.set("server.address", "a");
    tags.set(Tags.PEER_HOSTNAME, "b");
    tags.set(Tags.HTTP_METHOD, "c");
    tags.set("my.tag", "d");

    assertTrue(KnownTagCodec.isDirectionDependent(tags.getEntry("server.address")));
    assertTrue(KnownTagCodec.isDirectionDependent(tags.getEntry(Tags.PEER_HOSTNAME)));
    assertFalse(KnownTagCodec.isDirectionDependent(tags.getEntry(Tags.HTTP_METHOD)));
    assertFalse(KnownTagCodec.isDirectionDependent(tags.getEntry("my.tag")));
  }
}
