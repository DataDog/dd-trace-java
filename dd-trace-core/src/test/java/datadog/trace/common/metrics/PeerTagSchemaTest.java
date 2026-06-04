package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PeerTagSchemaTest {

  @Test
  void ofBuildsSchemaFromSetWithState() {
    Set<String> tags = new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service"));
    PeerTagSchema schema = PeerTagSchema.of(tags, "state-1234", HealthMetrics.NO_OP);

    assertArrayEquals(new String[] {"peer.hostname", "peer.service"}, schema.names);
    assertEquals("state-1234", schema.state);
    assertEquals(2, schema.size());
  }

  @Test
  void ofHandlesEmptySet() {
    PeerTagSchema schema =
        PeerTagSchema.of(Collections.<String>emptySet(), null, HealthMetrics.NO_OP);

    assertEquals(0, schema.size());
    assertEquals(0, schema.names.length);
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
            new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service")),
            "state-1",
            HealthMetrics.NO_OP);

    // Same content via a different Set reference -- this is the case the reconcile fast-path
    // depends on (Set returned from a fresh discovery cycle is content-equal to the prior one).
    Set<String> equivalentSet = new HashSet<>(Arrays.asList("peer.service", "peer.hostname"));
    assertTrue(schema.hasSameTagsAs(equivalentSet));
  }

  @Test
  void hasSameTagsAsReturnsFalseWhenSetGrew() {
    PeerTagSchema schema =
        PeerTagSchema.of(
            Collections.<String>singleton("peer.hostname"), "state-1", HealthMetrics.NO_OP);

    Set<String> larger = new HashSet<>(Arrays.asList("peer.hostname", "peer.service"));
    assertFalse(schema.hasSameTagsAs(larger));
  }

  @Test
  void hasSameTagsAsReturnsFalseWhenSetShrank() {
    PeerTagSchema schema =
        PeerTagSchema.of(
            new LinkedHashSet<>(Arrays.asList("peer.hostname", "peer.service")),
            "state-1",
            HealthMetrics.NO_OP);

    assertFalse(schema.hasSameTagsAs(Collections.<String>singleton("peer.hostname")));
  }

  @Test
  void hasSameTagsAsReturnsFalseWhenContentDifferent() {
    PeerTagSchema schema =
        PeerTagSchema.of(
            Collections.<String>singleton("peer.hostname"), "state-1", HealthMetrics.NO_OP);

    assertFalse(schema.hasSameTagsAs(Collections.<String>singleton("peer.service")));
  }

  @Test
  void hasSameTagsAsHandlesEmpty() {
    PeerTagSchema empty =
        PeerTagSchema.of(Collections.<String>emptySet(), "state-1", HealthMetrics.NO_OP);

    assertTrue(empty.hasSameTagsAs(Collections.<String>emptySet()));
    assertFalse(empty.hasSameTagsAs(Collections.<String>singleton("peer.hostname")));
  }
}
