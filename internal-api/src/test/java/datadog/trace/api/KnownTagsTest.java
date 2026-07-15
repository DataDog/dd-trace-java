package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property test for the generated {@link KnownTags} registry + the {@link KnownTagCodec.Resolver}
 * it registers. Tests resolver <em>behavior</em> (name &harr; id round-trip, intercepted flag,
 * reserved/stored tier, unique serials) over the tag names, rather than hard-coded ids — so it
 * stays valid as the generator reassigns serials/slots across builds.
 */
class KnownTagsTest {

  /** Every tag name in the registry (virtual + stored + trace-level). keyOf must resolve each. */
  static Stream<String> knownNames() {
    return Stream.of(
        // virtual / reserved
        "error",
        "service",
        "resource.name",
        "span.type",
        "origin",
        "sampling.priority",
        "manual.keep",
        "manual.drop",
        "measured",
        "analytics.sample_rate",
        // base (per-span)
        "_dd.parent_id",
        "component",
        "span.kind",
        "_dd.integration",
        "_dd.svc_src",
        "error.type",
        "error.message",
        "error.stack",
        // http
        "http.method",
        "http.status_code",
        "network.protocol.version",
        "http.url",
        "http.route",
        "http.hostname",
        "http.useragent",
        "http.query.string",
        "servlet.path",
        "servlet.context",
        "http.resend_count",
        // db
        "db.type",
        "db.instance",
        "db.operation",
        "db.user",
        "db.pool.name",
        "db.statement",
        // peer
        "peer.service",
        "_dd.peer.service.source",
        "_dd.peer.service.remapped_from",
        "peer.hostname",
        "peer.ipv4",
        "peer.ipv6",
        "peer.port",
        // view
        "view.name",
        // trace-level
        "_dd.base_service",
        "version",
        "env",
        "language",
        "runtime-id",
        "_dd.tracer_host",
        "_dd.git.commit.sha",
        "_dd.git.repository_url",
        "_dd.profiling.enabled",
        "_dd.dsm.enabled",
        "_dd.appsec.enabled",
        "_dd.djm.enabled",
        "_dd.civisibility.enabled");
  }

  @Test
  void resolverActiveOnceReferenced() {
    KnownTags.init(); // triggers <clinit> -> KnownTagCodec.register
    assertTrue(KnownTagCodec.isActive());
    assertEquals(KnownTags.SLOT_COUNT, KnownTagCodec.slotCount());
  }

  @ParameterizedTest
  @MethodSource("knownNames")
  void nameIdRoundTrips(String name) {
    long id = KnownTagCodec.keyOf(name);
    assertNotEquals(0L, id, "keyOf(" + name + ") should resolve");
    assertEquals(name, KnownTagCodec.nameOf(id), "nameOf(keyOf(" + name + "))");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "span.kind",
        "http.method",
        "http.url",
        "db.statement",
        "peer.service",
        "error",
        "service"
      })
  void interceptedTagsCarryFlag(String name) {
    assertTrue(KnownTagCodec.isIntercepted(KnownTagCodec.keyOf(name)), "intercepted: " + name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"component", "db.type", "version", "http.route", "peer.hostname"})
  void nonInterceptedTagsDoNotCarryFlag(String name) {
    assertFalse(KnownTagCodec.isIntercepted(KnownTagCodec.keyOf(name)), "not intercepted: " + name);
  }

  @Test
  void reservedVsStored() {
    assertTrue(KnownTagCodec.isReserved(KnownTagCodec.keyOf("error")), "error reserved");
    assertTrue(KnownTagCodec.isReserved(KnownTagCodec.keyOf("service")), "service reserved");
    assertTrue(KnownTagCodec.isStored(KnownTagCodec.keyOf("component")), "component stored");
    assertTrue(
        KnownTagCodec.isStored(KnownTagCodec.keyOf("_dd.base_service")), "base_service stored");
  }

  @Test
  void unknownNamesResolveToZero() {
    assertEquals(0L, KnownTagCodec.keyOf("definitely.not.a.known.tag"));
    assertEquals(0L, KnownTagCodec.keyOf("http.statuscode")); // close but not listed
    assertEquals(0L, KnownTagCodec.keyOf(""));
  }

  @Test
  void unknownIdsResolveToNullName() {
    assertNull(KnownTagCodec.nameOf(0L));
    assertNull(KnownTagCodec.nameOf(KnownTagCodec.tagId(9999, "made.up")));
  }

  @Test
  void globalSerialsAreUnique() {
    List<Integer> serials = new ArrayList<>();
    knownNames().forEach(n -> serials.add(KnownTagCodec.globalSerial(KnownTagCodec.keyOf(n))));
    assertEquals(new HashSet<>(serials).size(), serials.size(), "globalSerials must be unique");
    assertFalse(serials.contains(0), "no known name should resolve to serial 0");
  }
}
