package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity test for the keyOf substrate (slice 1): the {@link KnownTags} registry + the {@link
 * KnownTagCodec.Resolver} it registers. Verifies name &harr; id resolution without any dense store
 * — {@code keyOf}/{@code nameOf} depend only on globalSerial + name, not on the (dormant)
 * positional layout.
 */
class KnownTagsTest {

  /** (name, id) pairs — the full registry. keyOf returns the id verbatim (incl. INTERCEPTED). */
  static Stream<Arguments> knownTags() {
    return Stream.of(
        Arguments.of(Tags.ERROR, KnownTags.ERROR_ID),
        Arguments.of(DDTags.PARENT_ID, KnownTags.PARENT_ID),
        Arguments.of(DDTags.BASE_SERVICE, KnownTags.BASE_SERVICE_ID),
        Arguments.of(Tags.VERSION, KnownTags.VERSION_ID),
        Arguments.of(KnownTags.ENV, KnownTags.ENV_ID),
        Arguments.of(DDTags.DJM_ENABLED, KnownTags.DJM_ENABLED_ID),
        Arguments.of(DDTags.DSM_ENABLED, KnownTags.DSM_ENABLED_ID),
        Arguments.of(DDTags.TRACER_HOST, KnownTags.TRACER_HOST_ID),
        Arguments.of(DDTags.DD_INTEGRATION, KnownTags.INTEGRATION_ID),
        Arguments.of(DDTags.DD_SVC_SRC, KnownTags.SVC_SRC_ID),
        Arguments.of(Tags.PEER_SERVICE, KnownTags.PEER_SERVICE_ID),
        Arguments.of(DDTags.PEER_SERVICE_REMAPPED_FROM, KnownTags.PEER_SERVICE_REMAPPED_FROM_ID),
        Arguments.of(Tags.HTTP_METHOD, KnownTags.HTTP_METHOD_ID),
        Arguments.of(Tags.HTTP_ROUTE, KnownTags.HTTP_ROUTE_ID),
        Arguments.of(Tags.HTTP_URL, KnownTags.HTTP_URL_ID),
        Arguments.of(Tags.PEER_HOSTNAME, KnownTags.PEER_HOSTNAME_ID),
        Arguments.of(Tags.PEER_HOST_IPV4, KnownTags.PEER_HOST_IPV4_ID),
        Arguments.of(Tags.PEER_HOST_IPV6, KnownTags.PEER_HOST_IPV6_ID),
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
   * The subset flagged INTERCEPTED (sign bit) — must agree with the interceptor's needsIntercept.
   */
  static Stream<Arguments> interceptedTags() {
    return Stream.of(
        Arguments.of(KnownTags.ERROR_ID),
        Arguments.of(KnownTags.PEER_SERVICE_ID),
        Arguments.of(KnownTags.HTTP_METHOD_ID),
        Arguments.of(KnownTags.HTTP_URL_ID),
        Arguments.of(KnownTags.SPAN_KIND_ID));
  }

  @Test
  void resolverIsActiveOnceReferenced() {
    // referencing any constant triggers KnownTags.<clinit> -> KnownTagCodec.register
    assertTrue(KnownTags.ERROR_ID != 0L);
    assertTrue(KnownTagCodec.isActive());
    assertEquals(KnownTags.SLOT_COUNT, KnownTagCodec.slotCount());
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
  @MethodSource("knownTags")
  void nameHashMatchesEntryHash(String name, long id) {
    assertEquals(
        (int) TagMap.Entry._hash(name), KnownTagCodec.nameHash(id), "nameHash(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("interceptedTags")
  void interceptedTagsCarryFlag(long id) {
    assertTrue(KnownTagCodec.isIntercepted(id), "isIntercepted");
  }

  @Test
  void nonInterceptedTagsDoNotCarryFlag() {
    Set<Long> intercepted = new HashSet<>();
    interceptedTags().forEach(a -> intercepted.add((Long) a.get()[0]));
    knownTags()
        .forEach(
            a -> {
              long id = (Long) a.get()[1];
              if (!intercepted.contains(id)) {
                assertFalse(KnownTagCodec.isIntercepted(id), "not intercepted: " + a.get()[0]);
              }
            });
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
    assertNull(KnownTagCodec.nameOf(KnownTagCodec.tagId(9999, "made.up")));
  }

  @Test
  void errorIsReservedTheRestAreStored() {
    assertTrue(KnownTagCodec.isReserved(KnownTags.ERROR_ID), "ERROR reserved");
    assertFalse(KnownTagCodec.isStored(KnownTags.ERROR_ID), "ERROR not stored");
    knownTags()
        .forEach(
            a -> {
              long id = (Long) a.get()[1];
              if (id != KnownTags.ERROR_ID) {
                assertTrue(KnownTagCodec.isStored(id), "stored: " + a.get()[0]);
                assertFalse(KnownTagCodec.isReserved(id), "not reserved: " + a.get()[0]);
              }
            });
  }

  @Test
  void globalSerialsAreUnique() {
    List<Long> serials = new ArrayList<>();
    knownTags().forEach(a -> serials.add((long) KnownTagCodec.globalSerial((Long) a.get()[1])));
    assertEquals(serials.size(), new HashSet<>(serials).size(), "globalSerials must be unique");
  }
}
