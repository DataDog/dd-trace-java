package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PeerTagSchema}. Covers the {@link PeerTagSchema#hasSameTagsAs(Set)}
 * predicate that drives the aggregator's reconcile fast/slow path split, the factory shapes, and
 * the {@link PeerTagSchema#INTERNAL} singleton.
 */
class PeerTagSchemaTest {

  @Test
  void ofBuildsSchemaFromSetWithState() {
    Set<String> tags = new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service"));
    PeerTagSchema schema = PeerTagSchema.of(tags, "abc123");

    assertArrayEquals(new String[] {"peer.hostname", "peer.service"}, schema.names);
    assertEquals("abc123", schema.state);
    assertEquals(2, schema.size());
  }

  @Test
  void ofHandlesEmptySet() {
    PeerTagSchema schema = PeerTagSchema.of(Collections.<String>emptySet(), null);

    assertEquals(0, schema.size());
    assertEquals(0, schema.names.length);
    assertNull(schema.state);
  }

  @Test
  void internalSingletonCarriesBaseService() {
    assertEquals(1, PeerTagSchema.INTERNAL.size());
    assertEquals("_dd.base_service", PeerTagSchema.INTERNAL.names[0]);
  }

  @Test
  void hasSameTagsAsReturnsTrueForExactMatch() {
    PeerTagSchema schema =
        PeerTagSchema.of(
            new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service")), "state-1");

    // Same content via a different Set reference -- this is the case the reconcile fast-path
    // depends on (Set returned from a fresh discovery cycle is content-equal to the prior one).
    Set<String> equivalentSet = new HashSet<>(Arrays.asList("peer.service", "peer.hostname"));
    assertTrue(schema.hasSameTagsAs(equivalentSet));
  }

  @Test
  void hasSameTagsAsReturnsFalseWhenSetGrew() {
    PeerTagSchema schema =
        PeerTagSchema.of(Collections.<String>singleton("peer.hostname"), "state-1");

    Set<String> larger = new HashSet<>(Arrays.asList("peer.hostname", "peer.service"));
    assertFalse(schema.hasSameTagsAs(larger));
  }

  @Test
  void hasSameTagsAsReturnsFalseWhenSetShrank() {
    PeerTagSchema schema =
        PeerTagSchema.of(
            new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service")), "state-1");

    assertFalse(schema.hasSameTagsAs(Collections.<String>singleton("peer.hostname")));
  }

  @Test
  void hasSameTagsAsReturnsFalseWhenContentDifferent() {
    PeerTagSchema schema =
        PeerTagSchema.of(Collections.<String>singleton("peer.hostname"), "state-1");

    assertFalse(schema.hasSameTagsAs(Collections.<String>singleton("peer.service")));
  }

  @Test
  void hasSameTagsAsHandlesEmpty() {
    PeerTagSchema empty = PeerTagSchema.of(Collections.<String>emptySet(), "state-1");

    assertTrue(empty.hasSameTagsAs(Collections.<String>emptySet()));
    assertFalse(empty.hasSameTagsAs(Collections.<String>singleton("peer.hostname")));
  }

  @Test
  void equalsIsContentBasedOnNames() {
    PeerTagSchema a = PeerTagSchema.testSchema(new String[] {"peer.hostname", "peer.service"});
    PeerTagSchema b = PeerTagSchema.testSchema(new String[] {"peer.hostname", "peer.service"});

    assertEquals(a, b);
    assertEquals(b, a);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void equalsIgnoresState() {
    // state is a reconcile-bookkeeping field, not part of schema identity.
    PeerTagSchema early =
        PeerTagSchema.of(Collections.<String>singleton("peer.hostname"), "state-1");
    PeerTagSchema late =
        PeerTagSchema.of(Collections.<String>singleton("peer.hostname"), "state-2");

    assertEquals(early, late);
    assertEquals(early.hashCode(), late.hashCode());
  }

  @Test
  void equalsDistinguishesByOrder() {
    // names is positional -- the array index pairs with SpanSnapshot.peerTagValues. Schemas with
    // the same tags in different positions are NOT interchangeable.
    PeerTagSchema ab = PeerTagSchema.testSchema(new String[] {"a", "b"});
    PeerTagSchema ba = PeerTagSchema.testSchema(new String[] {"b", "a"});

    assertNotEquals(ab, ba);
  }

  @Test
  void equalsHandlesNullAndOtherTypes() {
    PeerTagSchema schema = PeerTagSchema.testSchema(new String[] {"peer.hostname"});

    assertNotEquals(schema, null);
    assertNotEquals(schema, "peer.hostname");
  }
}
