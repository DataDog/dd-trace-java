package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

/**
 * Checks the generated registry's name resolution and tag ID encoding. Datadog and OpenTelemetry
 * names resolve to the same ID, while output names remain namespace-specific.
 */
class KnownTagsTest {

  /** (name, id) pairs across the groups — idOf returns the id verbatim. */
  static Stream<Arguments> knownTags() {
    return Stream.of(
        Arguments.of(DDTags.PARENT_ID, KnownTags.DD_PARENT_ID),
        Arguments.of(DDTags.BASE_SERVICE, KnownTags.DD_BASE_SERVICE),
        Arguments.of(Tags.VERSION, KnownTags.VERSION),
        Arguments.of("env", KnownTags.ENV),
        Arguments.of(DDTags.DJM_ENABLED, KnownTags.DD_DJM_ENABLED),
        Arguments.of(DDTags.DSM_ENABLED, KnownTags.DD_DSM_ENABLED),
        Arguments.of(DDTags.TRACER_HOST, KnownTags.DD_TRACER_HOST),
        Arguments.of(DDTags.DD_INTEGRATION, KnownTags.DD_INTEGRATION),
        Arguments.of(DDTags.DD_SVC_SRC, KnownTags.DD_SVC_SRC),
        Arguments.of(Tags.PEER_SERVICE, KnownTags.PEER_SERVICE),
        Arguments.of(DDTags.PEER_SERVICE_REMAPPED_FROM, KnownTags.DD_PEER_SERVICE_REMAPPED_FROM),
        Arguments.of(Tags.HTTP_METHOD, KnownTags.HTTP_METHOD),
        Arguments.of(
            KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME, KnownTags.HTTP_REQUEST_METHOD_ORIGINAL),
        Arguments.of(Tags.HTTP_ROUTE, KnownTags.HTTP_ROUTE),
        Arguments.of(Tags.HTTP_URL, KnownTags.HTTP_URL),
        Arguments.of(Tags.HTTP_HOSTNAME, KnownTags.HTTP_HOSTNAME),
        Arguments.of(KnownTags.URL_PATH_NAME, KnownTags.URL_PATH),
        Arguments.of(KnownTags.HTTP_QUERY_STRING_NAME, KnownTags.HTTP_QUERY_STRING),
        Arguments.of(KnownTags.URL_QUERY_NAME, KnownTags.URL_QUERY),
        Arguments.of(KnownTags.URL_SCHEME_NAME, KnownTags.URL_SCHEME),
        Arguments.of(KnownTags.NETWORK_CLIENT_IP_NAME, KnownTags.NETWORK_CLIENT_IP),
        Arguments.of(Tags.PEER_HOSTNAME, KnownTags.PEER_HOSTNAME),
        Arguments.of(Tags.PEER_HOST_IPV4, KnownTags.PEER_IPV4),
        Arguments.of(Tags.PEER_HOST_IPV6, KnownTags.PEER_IPV6),
        Arguments.of(Tags.COMPONENT, KnownTags.COMPONENT),
        Arguments.of(Tags.SPAN_KIND, KnownTags.SPAN_KIND),
        Arguments.of(DDTags.LANGUAGE_TAG_KEY, KnownTags.LANGUAGE),
        Arguments.of(Tags.DB_TYPE, KnownTags.DB_TYPE),
        Arguments.of(Tags.DB_INSTANCE, KnownTags.DB_INSTANCE),
        Arguments.of(Tags.DB_USER, KnownTags.DB_USER),
        Arguments.of(Tags.DB_OPERATION, KnownTags.DB_OPERATION),
        Arguments.of(Tags.DB_POOL_NAME, KnownTags.DB_POOL_NAME));
  }

  /**
   * (otelName, canonicalId, datadogName) — the OpenTelemetry name resolves (idOf) to the canonical
   * tag's id; datadogNameOf returns the Datadog name and openTelemetryNameOf returns the OTel name.
   */
  static Stream<Arguments> otelNamedTags() {
    return Stream.of(
        Arguments.of("http.request.method", KnownTags.HTTP_METHOD, "http.method"),
        Arguments.of("http.response.status_code", KnownTags.HTTP_STATUS_CODE, "http.status_code"),
        Arguments.of("url.full", KnownTags.HTTP_URL, "http.url"),
        Arguments.of("user_agent.original", KnownTags.HTTP_USERAGENT, "http.useragent"),
        Arguments.of("client.address", KnownTags.HTTP_CLIENT_IP, "http.client_ip"),
        Arguments.of("db.system", KnownTags.DB_TYPE, "db.type"),
        Arguments.of("db.operation.name", KnownTags.DB_OPERATION, "db.operation"),
        Arguments.of("db.query.text", KnownTags.DB_STATEMENT, "db.statement"),
        Arguments.of("service.name", KnownTags.SERVICE, "service"));
  }

  /**
   * Trace-level tags (live on the TraceSegment's TagMap) — their id carries the LEVEL_TRACE bit.
   */
  static Stream<Arguments> traceLevelTags() {
    return Stream.of(
        Arguments.of(KnownTags.DD_BASE_SERVICE),
        Arguments.of(KnownTags.VERSION),
        Arguments.of(KnownTags.ENV),
        Arguments.of(KnownTags.LANGUAGE),
        Arguments.of(KnownTags.RUNTIME_ID),
        Arguments.of(KnownTags.DD_TRACER_HOST),
        Arguments.of(KnownTags.DD_DJM_ENABLED));
  }

  /** Span-level tags — their id leaves the LEVEL_TRACE bit clear. */
  static Stream<Arguments> spanLevelTags() {
    return Stream.of(
        Arguments.of(KnownTags.HTTP_METHOD),
        Arguments.of(KnownTags.HTTP_URL),
        Arguments.of(KnownTags.DB_TYPE),
        Arguments.of(KnownTags.COMPONENT),
        Arguments.of(KnownTags.SPAN_KIND),
        Arguments.of(KnownTags.PEER_SERVICE));
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void keyOfResolvesNameToId(String name, long id) {
    assertEquals(id, KnownTagCodec.idOf(name), "idOf(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void nameOfResolvesIdToName(String name, long id) {
    assertEquals(name, KnownTagCodec.nameOf(id), "nameOf(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("otelNamedTags")
  void otelNameResolvesToCanonicalId(String otelName, long id, String datadogName) {
    // Inbound (idOf) is many->one: both names land on the same canonical id.
    assertEquals(id, KnownTagCodec.idOf(otelName), "idOf(" + otelName + ")");
    assertEquals(id, KnownTagCodec.idOf(datadogName), "idOf(" + datadogName + ")");
  }

  @ParameterizedTest
  @MethodSource("otelNamedTags")
  void namespaceAccessorsReturnPerNamespaceName(String otelName, long id, String datadogName) {
    assertEquals(datadogName, KnownTagCodec.datadogNameOf(id), "datadogNameOf");
    assertEquals(otelName, KnownTagCodec.openTelemetryNameOf(id), "openTelemetryNameOf");
    // nameOf stays the Datadog name -- outbound is namespace-specific, not normalized to OTel.
    assertEquals(datadogName, KnownTagCodec.nameOf(id), "nameOf stays Datadog");
  }

  @ParameterizedTest
  @MethodSource("otelNamedTags")
  void openTelemetryTagOfReturnsTheRename(String otelName, long id, String datadogName) {
    assertEquals(otelName, KnownTagCodec.openTelemetryTagOf(id), "openTelemetryTagOf");
  }

  @Test
  void openTelemetryTagOfPassesThroughWhenThereIsNoRename() {
    // http.route declares no otel-name, so the OpenTelemetry namespace emits the Datadog name.
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.HTTP_ROUTE), "no declared rename");
    assertEquals(
        KnownTagCodec.nameOf(KnownTags.HTTP_ROUTE),
        KnownTagCodec.openTelemetryTagOf(KnownTags.HTTP_ROUTE),
        "pass-through falls back to the Datadog name");
  }

  @Test
  void openTelemetryTagOfIsNullForAnUnknownId() {
    // A custom tag has no registry name in any namespace; only its holder knows its key.
    assertNull(KnownTagCodec.openTelemetryTagOf(0L));
  }

  @Test
  void tagsWithoutOtelNameReturnNull() {
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.HTTP_ROUTE)); // no OTel name declared
    assertNull(KnownTagCodec.openTelemetryNameOf(0L)); // unknown id
  }

  @Test
  void unknownNamesResolveToZero() {
    assertEquals(0L, KnownTagCodec.idOf("definitely.not.a.known.tag"));
    assertEquals(0L, KnownTagCodec.idOf("http.statuscode")); // close-but-not-listed
    assertEquals(0L, KnownTagCodec.idOf(""));
  }

  @Test
  void networkPeerAddressIsNotADirectionFreeAliasForClientIp() {
    assertEquals(0L, KnownTagCodec.idOf("network.peer.address"));
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.NETWORK_CLIENT_IP));
  }

  @TableTest({
    "Scenario     | Kind     | Expected Name       ",
    "server       | server   | network.peer.address",
    "consumer     | consumer | network.peer.address",
    "client       | client   | network.client.ip   ",
    "producer     | producer | network.client.ip   ",
    "internal     | internal | network.client.ip   ",
    "unknown kind |          | network.client.ip   "
  })
  void networkClientIpIsRenamedOnlyOnInboundSpans(String kind, String expectedName) {
    assertEquals(
        expectedName,
        KnownTagCodec.openTelemetryTagOf(
            KnownTags.NETWORK_CLIENT_IP, KnownTagCodec.directionOfKind(kind)));
  }

  /**
   * The ports are concepts: one id per meaning, each with fixed names. peer.port is the Datadog
   * name of two of them, so it has no direction-free id; id to name never needs context.
   */
  @TableTest({
    "scenario           | id               | datadog     | otel       ",
    "server's own port  | server.port      | server.port | server.port",
    "remote server port | peer.server.port | peer.port   | server.port",
    "remote client port | peer.client.port | peer.port   | client.port"
  })
  void aConceptHasFixedNames(String id, String datadog, String otel) {
    long tagId = conceptId(id);
    assertTrue(KnownTagCodec.isConcept(tagId));
    assertEquals(datadog, KnownTagCodec.nameOf(tagId));
    assertEquals(otel, KnownTagCodec.openTelemetryNameOf(tagId));
    assertEquals(otel, KnownTagCodec.openTelemetryTagOf(tagId, KnownTagCodec.DIRECTION_NONE));
  }

  @Test
  void aConceptsNamesHaveNoDirectionFreeId() {
    assertEquals(0L, KnownTagCodec.idOf(Tags.PEER_PORT));
    assertEquals(0L, KnownTagCodec.idOf("server.port"));
    assertEquals(0L, KnownTagCodec.idOf("client.port"));
    assertFalse(KnownTagCodec.isConcept(KnownTags.PEER_HOSTNAME));
  }

  /**
   * A shared name resolves to a concept with the span's kind. A by-name write only resolves on a
   * server, client, producer or consumer span.
   */
  @TableTest({
    "scenario              | name        | kind | datadog          | write            | otel            ",
    "peer.port on server   | peer.port   | 1    | peer.client.port | peer.client.port |                 ",
    "peer.port on client   | peer.port   | 2    | peer.server.port | peer.server.port |                 ",
    "peer.port on consumer | peer.port   | 4    | peer.server.port | peer.server.port |                 ",
    "peer.port on internal | peer.port   | 5    |                  |                  |                 ",
    "server.port on server | server.port | 1    | server.port      | server.port      | server.port     ",
    "server.port on client | server.port | 2    |                  |                  | peer.server.port",
    "client.port on server | client.port | 1    |                  |                  | peer.client.port",
    "a plain name          | my.tag      | 2    |                  |                  |                 "
  })
  void aSharedNameResolvesByKind(String name, int kind, String datadog, String write, String otel) {
    assertEquals(datadog, idName(KnownTagCodec.datadogIdOf(name, kind)));
    assertEquals(write, idName(KnownTagCodec.datadogWriteIdOf(name, kind)));
    assertEquals(otel, idName(KnownTagCodec.openTelemetryIdOf(name, kind)));
  }

  @Test
  void theConceptsSharingANameAreListed() {
    assertArrayEquals(
        new long[] {KnownTags.PEER_CLIENT_PORT, KnownTags.PEER_SERVER_PORT},
        KnownTagCodec.conceptsNamed(Tags.PEER_PORT));
    assertArrayEquals(
        new long[] {KnownTags.SERVER_PORT}, KnownTagCodec.conceptsNamed("server.port"));
    assertEquals(0, KnownTagCodec.conceptsNamed("my.tag").length);
  }

  private static long conceptId(String id) {
    switch (id) {
      case "server.port":
        return KnownTags.SERVER_PORT;
      case "peer.server.port":
        return KnownTags.PEER_SERVER_PORT;
      default:
        return KnownTags.PEER_CLIENT_PORT;
    }
  }

  /** A concept id's id name (not its Datadog name), so a table can tell the concepts apart. */
  private static String idName(long tagId) {
    if (tagId == 0) {
      return null;
    } else if (tagId == KnownTags.SERVER_PORT) {
      return "server.port";
    } else if (tagId == KnownTags.PEER_SERVER_PORT) {
      return "peer.server.port";
    } else if (tagId == KnownTags.PEER_CLIENT_PORT) {
      return "peer.client.port";
    }
    return KnownTagCodec.nameOf(tagId);
  }

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
  void theDirectionOfASpanFollowsItsKind(String kind, int direction) {
    assertEquals(direction, KnownTagCodec.directionOfKind(kind));
  }

  @Test
  void unknownIdsResolveToNullName() {
    assertNull(KnownTagCodec.nameOf(0L));
    assertNull(KnownTagCodec.nameOf(KnownTagCodec.makeTagId(9999))); // serial with no assigned tag
  }

  /**
   * A mixin declares tags for the span types its {@code applies:} names. {@code ci_visibility}
   * applies to {@code test}, which the conventions do not model yet -- so these tags belong to no
   * concrete type's resolved set. They must still be registered: an id is identity, and identity
   * does not depend on layout. Building the registry by resolving concrete types instead dropped
   * all four silently, leaving idOf to report live CI Visibility tags as unknown.
   */
  @ParameterizedTest
  @MethodSource("declarationOnlyMixinTags")
  void mixinTagsAreRegisteredEvenWhenTheirSpanTypeIsNotModeled(String name, long id) {
    assertEquals(id, KnownTagCodec.idOf(name), "idOf(" + name + ")");
    assertEquals(name, KnownTagCodec.nameOf(id), "nameOf(" + name + ")");
  }

  /** Tags reachable only through a mixin whose {@code applies:} target is not modeled. */
  static Stream<Arguments> declarationOnlyMixinTags() {
    return Stream.of(
        Arguments.of(KnownTags.TEST_NAME_NAME, KnownTags.TEST_NAME),
        Arguments.of(KnownTags.TEST_SUITE_NAME, KnownTags.TEST_SUITE),
        Arguments.of(KnownTags.TEST_STATUS_NAME, KnownTags.TEST_STATUS),
        Arguments.of(KnownTags.TEST_FRAMEWORK_NAME, KnownTags.TEST_FRAMEWORK));
  }

  @Test
  void globalSerialsAreUnique() {
    List<Long> serials = new ArrayList<>();
    knownTags().forEach(a -> serials.add((long) KnownTagCodec.serialNum((Long) a.get()[1])));
    assertEquals(serials.size(), new HashSet<>(serials).size(), "globalSerials must be unique");
  }

  @ParameterizedTest
  @MethodSource("traceLevelTags")
  void traceLevelTagsCarryLevelBit(long id) {
    assertTrue(KnownTagCodec.isTraceLevel(id), "isTraceLevel");
  }

  @ParameterizedTest
  @MethodSource("spanLevelTags")
  void spanLevelTagsClearLevelBit(long id) {
    assertFalse(KnownTagCodec.isTraceLevel(id), "not trace-level");
  }

  @Test
  void levelBitCompositionRoundTrips() {
    long spanId = KnownTagCodec.makeTagId(300); // no level bit
    assertFalse(KnownTagCodec.isTraceLevel(spanId));
    long traceId = KnownTagCodec.traceLevel(spanId);
    assertTrue(KnownTagCodec.isTraceLevel(traceId));
    // level bit is orthogonal to the serial — it survives setting the bit
    assertEquals(KnownTagCodec.serialNum(spanId), KnownTagCodec.serialNum(traceId));
    assertEquals(traceId, KnownTagCodec.traceLevel(traceId), "traceLevel is idempotent");
  }

  @Test
  void serialEncodingRoundTrips() {
    long id = KnownTagCodec.makeTagId(263);
    assertEquals(263, KnownTagCodec.serialNum(id));
    assertFalse(KnownTagCodec.isTraceLevel(id));
  }

  @Test
  void theInterceptedBitMarksTheTracerOverlayTags() {
    assertTrue(KnownTagCodec.isIntercepted(KnownTags.SPAN_KIND));
    assertFalse(KnownTagCodec.isIntercepted(KnownTags.HTTP_ROUTE));
  }

  @Test
  void isKnownRejectsTheNoTagSerialAndSerialsPastTheLimit() {
    assertTrue(KnownTagCodec.isKnown(KnownTags.HTTP_ROUTE));
    assertFalse(KnownTagCodec.isKnown(0L));
    assertFalse(KnownTagCodec.isKnown(KnownTagCodec.makeTagId(KnownTagCodec.serialLimit())));
  }

  @Test
  void canonicalTagNameMapsAnOpenTelemetryRenameToItsDatadogName() {
    assertEquals(Tags.HTTP_METHOD, KnownTagCodec.canonicalTagName(KnownTags.HTTP_METHOD_OTEL_NAME));
    assertEquals(Tags.HTTP_METHOD, KnownTagCodec.canonicalTagName(Tags.HTTP_METHOD));
    assertEquals("my.custom.tag", KnownTagCodec.canonicalTagName("my.custom.tag"));
  }

  @TableTest({
    "scenario | kind       | direction",
    "no kind  |            | -1       ",
    "server   | 'server'   | 0        ",
    "consumer | 'consumer' | 0        ",
    "client   | 'client'   | 1        ",
    "producer | 'producer' | 1        ",
    "internal | 'internal' | 2        ",
    "broker   | 'broker'   | -1       "
  })
  void directionOfKind(String kind, int direction) {
    assertEquals(direction, KnownTagCodec.directionOfKind(kind));
  }

  @Test
  void directionOfReadsTheMapsSpanKind() {
    TagMap tags = TagMap.create();
    assertEquals(KnownTagCodec.DIRECTION_UNKNOWN, KnownTagCodec.directionOf(tags));

    tags.set(KnownTags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    assertEquals(KnownTagCodec.DIRECTION_OUTBOUND, KnownTagCodec.directionOf(tags));
  }

  @Test
  void anEntrysDirectionalOpenTelemetryTagFallsBackToItsNameForACustomTag() {
    TagMap.Entry custom = TagMap.Entry.newAnyEntry("my.custom.tag", "value");
    assertEquals("my.custom.tag", custom.openTelemetryTag(KnownTagCodec.DIRECTION_OUTBOUND));

    TagMap.Entry serverPort = TagMap.Entry.newAnyEntry(KnownTags.PEER_SERVER_PORT, 443);
    assertEquals("server.port", serverPort.openTelemetryTag(KnownTagCodec.DIRECTION_OUTBOUND));
    assertEquals("peer.port", serverPort.tag());
  }

  /** OpenTelemetry names whose tag depends on the span's direction, resolved by that direction. */
  @TableTest({
    "scenario                    | name                 | direction | expected         ",
    "server.address inbound      | server.address       | 0         | http.hostname    ",
    "server.address outbound     | server.address       | 1         | peer.hostname    ",
    "server.address internal     | server.address       | 2         |                  ",
    "server.address no direction | server.address       | -1        |                  ",
    "client.port outbound        | client.port          | 1         |                  ",
    "client address inbound      | network.peer.address | 0         | network.client.ip",
    "direction-free name         | http.request.method  | 1         |                  ",
    "Datadog name                | peer.hostname        | 1         |                  "
  })
  void aDirectionScopedNameResolvesByDirection(String name, int direction, String expected) {
    long tagId = KnownTagCodec.directionalIdOf(name, direction);
    assertEquals(expected, tagId == 0 ? null : KnownTagCodec.nameOf(tagId));
  }

  @Test
  void onlyDirectionScopedNamesAreReportedAsSuch() {
    assertTrue(KnownTagCodec.isDirectionScoped("server.address"));
    assertFalse(KnownTagCodec.isDirectionScoped("http.request.method"));
    assertFalse(KnownTagCodec.isDirectionScoped(Tags.PEER_HOSTNAME));
    assertFalse(KnownTagCodec.isDirectionScoped("my.custom.tag"));
  }

  /** server.address has no direction-free tag. */
  @Test
  void aDirectionDependentNameHasNoDirectionFreeTag() {
    assertEquals(0L, KnownTagCodec.idOf("server.address"));
  }

  @Test
  void eachDirectionNamesItsTagsInOpenTelemetryTerms() {
    assertEquals(KnownTags.PEER_HOSTNAME, KnownTags.Outbound.SERVER_ADDRESS);
    assertEquals(KnownTags.HTTP_HOSTNAME, KnownTags.Inbound.SERVER_ADDRESS);
    assertEquals(KnownTags.HTTP_URL, KnownTags.Outbound.URL_FULL);
  }

  /** Every per-direction alias names, in its direction, a tag emitted under that alias. */
  @TableTest({
    "scenario | holder   | direction",
    "inbound  | Inbound  | 0        ",
    "outbound | Outbound | 1        ",
    "internal | Internal | 2        "
  })
  void perDirectionAliasesAgreeWithTheDirectionalName(String holder, int direction)
      throws Exception {
    Field[] fields = Class.forName(KnownTags.class.getName() + "$" + holder).getFields();
    assertTrue(fields.length > 0, holder);
    for (Field field : fields) {
      long tagId = field.getLong(null);
      String otelName = KnownTagCodec.openTelemetryTagOf(tagId, direction);
      String expected = otelName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
      assertEquals(expected, field.getName(), holder + "." + field.getName());
    }
  }
}
