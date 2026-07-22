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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity test for the keyOf substrate: the generated {@link KnownTags} registry + the {@link
 * KnownTagCodec.Resolver} it registers. Verifies name &harr; id resolution and the intercepted /
 * reserved / stored partitioning. {@code keyOf}/{@code nameOf} depend only on globalSerial + name,
 * not on the (dormant) positional layout, so this is independent of the group/field-decl
 * coordinates the tag registry assigns.
 */
class KnownTagsTest {

  /** (name, id) pairs across the groups — keyOf returns the id verbatim (incl. INTERCEPTED). */
  static Stream<Arguments> knownTags() {
    return Stream.of(
        Arguments.of(Tags.ERROR, KnownTags.ERROR),
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
        Arguments.of(Tags.HTTP_ROUTE, KnownTags.HTTP_ROUTE),
        Arguments.of(Tags.HTTP_URL, KnownTags.HTTP_URL),
        Arguments.of(Tags.PEER_HOSTNAME, KnownTags.PEER_HOSTNAME),
        Arguments.of(Tags.PEER_HOST_IPV4, KnownTags.PEER_IPV4),
        Arguments.of(Tags.PEER_HOST_IPV6, KnownTags.PEER_IPV6),
        Arguments.of(Tags.PEER_PORT, KnownTags.PEER_PORT),
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
   * The subset flagged INTERCEPTED (sign bit) — must agree with the interceptor's needsIntercept.
   */
  static Stream<Arguments> interceptedTags() {
    return Stream.of(
        Arguments.of(KnownTags.ERROR),
        Arguments.of(KnownTags.PEER_SERVICE),
        Arguments.of(KnownTags.HTTP_METHOD),
        Arguments.of(KnownTags.HTTP_URL),
        Arguments.of(KnownTags.SPAN_KIND));
  }

  @BeforeAll
  static void registerResolver() {
    // Generated ids are compile-time constants (literal), so a constant reference is inlined and
    // never triggers KnownTags.<clinit>. init() forces class-load -> KnownTagCodec.register.
    KnownTags.init();
  }

  @Test
  void resolverIsActiveAfterInit() {
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
    assertNull(KnownTagCodec.nameOf(KnownTagCodec.tagId(9999))); // serial with no assigned tag
  }

  @Test
  void errorIsReservedTheRestAreStored() {
    assertTrue(KnownTagCodec.isReserved(KnownTags.ERROR), "ERROR reserved");
    assertFalse(KnownTagCodec.isStored(KnownTags.ERROR), "ERROR not stored");
    knownTags()
        .forEach(
            a -> {
              long id = (Long) a.get()[1];
              if (id != KnownTags.ERROR) {
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
