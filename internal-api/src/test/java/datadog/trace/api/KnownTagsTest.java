package datadog.trace.api;

import static datadog.trace.api.KnownTagCodec.DIRECTION_INBOUND;
import static datadog.trace.api.KnownTagCodec.DIRECTION_OUTBOUND;
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

  /** (name, id) pairs across the groups — keyOf returns the id verbatim. */
  static Stream<Arguments> knownTags() {
    return Stream.of(
        Arguments.of(DDTags.PARENT_ID, KnownTags.DD_PARENT_ID),
        Arguments.of(DDTags.BASE_SERVICE, KnownTags.DD_BASE_SERVICE_ID),
        Arguments.of(Tags.VERSION, KnownTags.VERSION_ID),
        Arguments.of("env", KnownTags.ENV_ID),
        Arguments.of(DDTags.DJM_ENABLED, KnownTags.DD_DJM_ENABLED_ID),
        Arguments.of(DDTags.DSM_ENABLED, KnownTags.DD_DSM_ENABLED_ID),
        Arguments.of(DDTags.TRACER_HOST, KnownTags.DD_TRACER_HOST_ID),
        Arguments.of(DDTags.DD_INTEGRATION, KnownTags.DD_INTEGRATION_ID),
        Arguments.of(DDTags.DD_SVC_SRC, KnownTags.DD_SVC_SRC_ID),
        Arguments.of(Tags.PEER_SERVICE, KnownTags.PEER_SERVICE_ID),
        Arguments.of(DDTags.PEER_SERVICE_REMAPPED_FROM, KnownTags.DD_PEER_SERVICE_REMAPPED_FROM_ID),
        Arguments.of(Tags.HTTP_METHOD, KnownTags.HTTP_METHOD_ID),
        Arguments.of(
            KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME, KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_ID),
        Arguments.of(Tags.HTTP_ROUTE, KnownTags.HTTP_ROUTE_ID),
        Arguments.of(Tags.HTTP_URL, KnownTags.HTTP_URL_ID),
        Arguments.of(Tags.HTTP_HOSTNAME, KnownTags.HTTP_HOSTNAME_ID),
        Arguments.of(KnownTags.SERVER_PORT_NAME, KnownTags.SERVER_PORT_ID),
        Arguments.of(KnownTags.URL_PATH_NAME, KnownTags.URL_PATH_ID),
        Arguments.of(KnownTags.HTTP_QUERY_STRING_NAME, KnownTags.HTTP_QUERY_STRING_ID),
        Arguments.of(KnownTags.URL_QUERY_NAME, KnownTags.URL_QUERY_ID),
        Arguments.of(KnownTags.URL_SCHEME_NAME, KnownTags.URL_SCHEME_ID),
        Arguments.of(KnownTags.NETWORK_CLIENT_IP_NAME, KnownTags.NETWORK_CLIENT_IP_ID),
        Arguments.of(Tags.PEER_HOSTNAME, KnownTags.PEER_HOSTNAME_ID),
        Arguments.of(Tags.PEER_HOST_IPV4, KnownTags.PEER_IPV4_ID),
        Arguments.of(Tags.PEER_HOST_IPV6, KnownTags.PEER_IPV6_ID),
        Arguments.of(Tags.COMPONENT, KnownTags.COMPONENT_ID),
        Arguments.of(Tags.SPAN_KIND, KnownTags.SPAN_KIND_ID),
        Arguments.of(DDTags.LANGUAGE_TAG_KEY, KnownTags.LANGUAGE_ID),
        Arguments.of(Tags.DB_TYPE, KnownTags.DB_TYPE_ID),
        Arguments.of(Tags.DB_INSTANCE, KnownTags.DB_INSTANCE_ID),
        Arguments.of(Tags.DB_USER, KnownTags.DB_USER_ID),
        Arguments.of(Tags.DB_OPERATION, KnownTags.DB_OPERATION_ID),
        Arguments.of(Tags.DB_POOL_NAME, KnownTags.DB_POOL_NAME_ID));
  }

  /**
   * (otelName, canonicalId, datadogName) — the OpenTelemetry name resolves (keyOf) to the canonical
   * tag's id; datadogNameOf returns the Datadog name and openTelemetryNameOf returns the OTel name.
   */
  static Stream<Arguments> otelNamedTags() {
    return Stream.of(
        Arguments.of("http.request.method", KnownTags.HTTP_METHOD_ID, "http.method"),
        Arguments.of(
            "http.response.status_code", KnownTags.HTTP_STATUS_CODE_ID, "http.status_code"),
        Arguments.of("url.full", KnownTags.HTTP_URL_ID, "http.url"),
        Arguments.of("user_agent.original", KnownTags.HTTP_USERAGENT_ID, "http.useragent"),
        Arguments.of("client.address", KnownTags.HTTP_CLIENT_IP_ID, "http.client_ip"),
        Arguments.of("db.system", KnownTags.DB_TYPE_ID, "db.type"),
        Arguments.of("db.operation.name", KnownTags.DB_OPERATION_ID, "db.operation"),
        Arguments.of("db.query.text", KnownTags.DB_STATEMENT_ID, "db.statement"),
        Arguments.of("service.name", KnownTags.SERVICE_ID, "service"));
  }

  /**
   * Trace-level tags (live on the TraceSegment's TagMap) — their id carries the LEVEL_TRACE bit.
   */
  static Stream<Arguments> traceLevelTags() {
    return Stream.of(
        Arguments.of(KnownTags.DD_BASE_SERVICE_ID),
        Arguments.of(KnownTags.VERSION_ID),
        Arguments.of(KnownTags.ENV_ID),
        Arguments.of(KnownTags.LANGUAGE_ID),
        Arguments.of(KnownTags.RUNTIME_ID),
        Arguments.of(KnownTags.DD_TRACER_HOST_ID),
        Arguments.of(KnownTags.DD_DJM_ENABLED_ID));
  }

  /** Span-level tags — their id leaves the LEVEL_TRACE bit clear. */
  static Stream<Arguments> spanLevelTags() {
    return Stream.of(
        Arguments.of(KnownTags.HTTP_METHOD_ID),
        Arguments.of(KnownTags.HTTP_URL_ID),
        Arguments.of(KnownTags.DB_TYPE_ID),
        Arguments.of(KnownTags.COMPONENT_ID),
        Arguments.of(KnownTags.SPAN_KIND_ID),
        Arguments.of(KnownTags.PEER_SERVICE_ID));
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void keyOfResolvesNameToId(String name, long id) {
    assertEquals(id, KnownTagCodec.keyOf(name), "keyOf(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void nameOfResolvesIdToName(String name, long id) {
    assertEquals(name, KnownTagCodec.nameOf(id), "nameOf(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("otelNamedTags")
  void otelNameResolvesToCanonicalId(String otelName, long id, String datadogName) {
    // Inbound (keyOf) is many->one: both names land on the same canonical id.
    assertEquals(id, KnownTagCodec.keyOf(otelName), "keyOf(" + otelName + ")");
    assertEquals(id, KnownTagCodec.keyOf(datadogName), "keyOf(" + datadogName + ")");
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
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.HTTP_ROUTE_ID), "no declared rename");
    assertEquals(
        KnownTagCodec.nameOf(KnownTags.HTTP_ROUTE_ID),
        KnownTagCodec.openTelemetryTagOf(KnownTags.HTTP_ROUTE_ID),
        "pass-through falls back to the Datadog name");
  }

  @Test
  void openTelemetryTagOfIsNullForAnUnknownId() {
    // A custom tag has no registry name in any namespace; only its holder knows its key.
    assertNull(KnownTagCodec.openTelemetryTagOf(0L));
  }

  @Test
  void tagsWithoutOtelNameReturnNull() {
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.HTTP_ROUTE_ID)); // no OTel name declared
    assertNull(KnownTagCodec.openTelemetryNameOf(0L)); // unknown id
  }

  @Test
  void unknownNamesResolveToZero() {
    assertEquals(0L, KnownTagCodec.keyOf("definitely.not.a.known.tag"));
    assertEquals(0L, KnownTagCodec.keyOf("http.statuscode")); // close-but-not-listed
    assertEquals(0L, KnownTagCodec.keyOf(""));
  }

  @Test
  void networkPeerAddressIsNotADirectionFreeAliasForClientIp() {
    assertEquals(0L, KnownTagCodec.keyOf("network.peer.address"));
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.NETWORK_CLIENT_IP_ID));
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
            KnownTags.NETWORK_CLIENT_IP_ID, KnownTagCodec.directionOfKind(kind)));
  }

  /**
   * {@code peer.port} is declared once per direction, but it is one tag with one id: only its
   * OpenTelemetry name depends on the span's direction ({@code client.port} inbound, {@code
   * server.port} outbound).
   */
  @Test
  void aNameDeclaredPerDirectionIsOneTag() {
    assertEquals(KnownTags.PEER_PORT_ID, KnownTagCodec.keyOf(Tags.PEER_PORT));
    assertEquals(Tags.PEER_PORT, KnownTagCodec.nameOf(KnownTags.PEER_PORT_ID));
    assertNull(KnownTagCodec.openTelemetryNameOf(KnownTags.PEER_PORT_ID));
    assertEquals(Tags.PEER_PORT, KnownTagCodec.openTelemetryTagOf(KnownTags.PEER_PORT_ID));
    assertEquals(
        "client.port",
        KnownTagCodec.openTelemetryTagOf(KnownTags.PEER_PORT_ID, KnownTagCodec.DIRECTION_INBOUND));
    assertEquals(
        "server.port",
        KnownTagCodec.openTelemetryTagOf(KnownTags.PEER_PORT_ID, KnownTagCodec.DIRECTION_OUTBOUND));
    assertEquals(
        Tags.PEER_PORT,
        KnownTagCodec.openTelemetryTagOf(KnownTags.PEER_PORT_ID, KnownTagCodec.DIRECTION_NONE));
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
   * all four silently, leaving keyOf to report live CI Visibility tags as unknown.
   */
  @ParameterizedTest
  @MethodSource("declarationOnlyMixinTags")
  void mixinTagsAreRegisteredEvenWhenTheirSpanTypeIsNotModeled(String name, long id) {
    assertEquals(id, KnownTagCodec.keyOf(name), "keyOf(" + name + ")");
    assertEquals(name, KnownTagCodec.nameOf(id), "nameOf(" + name + ")");
  }

  /** Tags reachable only through a mixin whose {@code applies:} target is not modeled. */
  static Stream<Arguments> declarationOnlyMixinTags() {
    return Stream.of(
        Arguments.of(KnownTags.TEST_NAME, KnownTags.TEST_NAME_ID),
        Arguments.of(KnownTags.TEST_SUITE_NAME, KnownTags.TEST_SUITE_ID),
        Arguments.of(KnownTags.TEST_STATUS_NAME, KnownTags.TEST_STATUS_ID),
        Arguments.of(KnownTags.TEST_FRAMEWORK_NAME, KnownTags.TEST_FRAMEWORK_ID));
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
    assertTrue(KnownTagCodec.isIntercepted(KnownTags.SPAN_KIND_ID));
    assertFalse(KnownTagCodec.isIntercepted(KnownTags.HTTP_ROUTE_ID));
  }

  @Test
  void isKnownRejectsTheNoTagSerialAndSerialsPastTheLimit() {
    assertTrue(KnownTagCodec.isKnown(KnownTags.HTTP_ROUTE_ID));
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

    tags.set(KnownTags.SPAN_KIND_ID, Tags.SPAN_KIND_CLIENT);
    assertEquals(KnownTagCodec.DIRECTION_OUTBOUND, KnownTagCodec.directionOf(tags));
  }

  @Test
  void anEntrysDirectionalOpenTelemetryTagFallsBackToItsNameForACustomTag() {
    TagMap.Entry custom = TagMap.Entry.newAnyEntry("my.custom.tag", "value");
    assertEquals("my.custom.tag", custom.openTelemetryTag(KnownTagCodec.DIRECTION_OUTBOUND));

    TagMap.Entry peerPort = TagMap.Entry.newAnyEntry(KnownTags.PEER_PORT_ID, 443);
    assertEquals(
        KnownTagCodec.openTelemetryTagOf(KnownTags.PEER_PORT_ID, KnownTagCodec.DIRECTION_OUTBOUND),
        peerPort.openTelemetryTag(KnownTagCodec.DIRECTION_OUTBOUND));
  }

  /** OpenTelemetry names whose tag depends on the span's direction, resolved by that direction. */
  @TableTest({
    "scenario                    | name                 | direction | expected         ",
    "server.address inbound      | server.address       | 0         | http.hostname    ",
    "server.address outbound     | server.address       | 1         | peer.hostname    ",
    "server.address internal     | server.address       | 2         |                  ",
    "server.address no direction | server.address       | -1        |                  ",
    "server.port outbound        | server.port          | 1         | peer.port        ",
    "server.port inbound         | server.port          | 0         | server.port      ",
    "server.port internal        | server.port          | 2         | server.port      ",
    "client.port inbound         | client.port          | 0         | peer.port        ",
    "client.port outbound        | client.port          | 1         |                  ",
    "client address inbound      | network.peer.address | 0         | network.client.ip",
    "direction-free name         | http.request.method  | 1         |                  ",
    "Datadog name                | peer.hostname        | 1         |                  "
  })
  void aDirectionScopedNameResolvesByDirection(String name, int direction, String expected) {
    long tagId = KnownTagCodec.directionalKeyOf(name, direction);
    assertEquals(expected, tagId == 0 ? null : KnownTagCodec.nameOf(tagId));
  }

  @Test
  void onlyDirectionScopedNamesAreReportedAsSuch() {
    assertTrue(KnownTagCodec.isDirectionScoped("server.address"));
    assertTrue(KnownTagCodec.isDirectionScoped("server.port"));
    assertTrue(KnownTagCodec.isDirectionScoped("client.port"));
    assertFalse(KnownTagCodec.isDirectionScoped("http.request.method"));
    assertFalse(KnownTagCodec.isDirectionScoped(Tags.PEER_HOSTNAME));
    assertFalse(KnownTagCodec.isDirectionScoped("my.custom.tag"));
  }

  /**
   * server.port is a Datadog name too: without a direction it is its own tag, the server's port.
   * Only OpenTelemetry-side resolution maps it to peer.port on outbound spans.
   */
  @Test
  void aSharedNameIsItsOwnTagWithoutADirection() {
    assertEquals(KnownTags.SERVER_PORT_ID, KnownTagCodec.keyOf("server.port"));
    assertEquals("server.port", KnownTagCodec.nameOf(KnownTags.SERVER_PORT_ID));
    assertEquals(0L, KnownTagCodec.keyOf("server.address"));
    assertEquals(
        "server.port",
        KnownTagCodec.openTelemetryTagOf(KnownTags.PEER_PORT_ID, DIRECTION_OUTBOUND));
    assertEquals(
        "server.port",
        KnownTagCodec.openTelemetryTagOf(KnownTags.SERVER_PORT_ID, DIRECTION_INBOUND));
  }

  @Test
  void eachDirectionNamesItsTagsInOpenTelemetryTerms() {
    assertEquals(KnownTags.PEER_HOSTNAME_ID, KnownTags.Outbound.SERVER_ADDRESS_ID);
    assertEquals(KnownTags.PEER_PORT_ID, KnownTags.Outbound.SERVER_PORT_ID);
    assertEquals(KnownTags.HTTP_HOSTNAME_ID, KnownTags.Inbound.SERVER_ADDRESS_ID);
    assertEquals(KnownTags.SERVER_PORT_ID, KnownTags.Inbound.SERVER_PORT_ID);
    assertEquals(KnownTags.PEER_PORT_ID, KnownTags.Inbound.CLIENT_PORT_ID);
    assertEquals(KnownTags.HTTP_URL_ID, KnownTags.Outbound.URL_FULL_ID);
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
      String expected = otelName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_ID";
      assertEquals(expected, field.getName(), holder + "." + field.getName());
    }
  }
}
