package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity test for the keyOf substrate: the generated {@link KnownTags} registry + the {@link
 * KnownTagCodec.Resolver} it registers. Verifies name &harr; id resolution and the serial/level
 * partitioning of an id. {@code keyOf} is many&rarr;one (a Datadog or an OpenTelemetry name both
 * land on the one id) and the per-namespace accessors take it back out. A tag id is identity only,
 * so nothing here depends on how a tag is stored -- or on how it is set.
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
        Arguments.of(Tags.HTTP_ROUTE, KnownTags.HTTP_ROUTE_ID),
        Arguments.of(Tags.HTTP_URL, KnownTags.HTTP_URL_ID),
        Arguments.of(Tags.PEER_HOSTNAME, KnownTags.PEER_HOSTNAME_ID),
        Arguments.of(Tags.PEER_HOST_IPV4, KnownTags.PEER_IPV4_ID),
        Arguments.of(Tags.PEER_HOST_IPV6, KnownTags.PEER_IPV6_ID),
        Arguments.of(Tags.PEER_PORT, KnownTags.PEER_PORT_ID),
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
        Arguments.of("server.address", KnownTags.HTTP_HOSTNAME_ID, "http.hostname"),
        Arguments.of("user_agent.original", KnownTags.HTTP_USERAGENT_ID, "http.useragent"),
        Arguments.of("url.query", KnownTags.HTTP_QUERY_STRING_ID, "http.query.string"),
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
}
