package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PropagationTags OPM round-trip")
class PropagationTagsOpmTest {

  private PropagationTags.Factory factory;

  @BeforeEach
  void setUp() {
    factory = PropagationTags.factory();
  }

  @Test
  @DisplayName("Datadog: extract _dd.p.opm from x-datadog-tags then re-serialize it")
  void datadogRoundTrip() {
    PropagationTags tags = factory.fromHeaderValue(DATADOG, "_dd.p.opm=abc123def0");
    assertNotNull(tags.getOrgPropagationMarker());
    assertEquals("abc123def0", tags.getOrgPropagationMarker().toString());
    String header = tags.headerValue(DATADOG);
    assertNotNull(header);
    assertTrue(header.contains("_dd.p.opm=abc123def0"), "header was: " + header);
  }

  @Test
  @DisplayName("W3C: extract t.opm from tracestate dd= section then re-serialize it")
  void w3cRoundTrip() {
    PropagationTags tags = factory.fromHeaderValue(W3C, "dd=t.opm:abc123def0");
    assertNotNull(tags.getOrgPropagationMarker());
    assertEquals("abc123def0", tags.getOrgPropagationMarker().toString());
    String header = tags.headerValue(W3C);
    assertNotNull(header);
    assertTrue(header.contains("t.opm:abc123def0"), "header was: " + header);
  }

  @Test
  @DisplayName("update overrides any previously extracted OPM (W3C)")
  void updateOverridesExtractedW3C() {
    PropagationTags tags = factory.fromHeaderValue(W3C, "dd=t.opm:upstream-abc");
    tags.updateOrgPropagationMarker("local-xyz");
    assertEquals("local-xyz", tags.getOrgPropagationMarker().toString());
    String header = tags.headerValue(W3C);
    assertNotNull(header);
    assertTrue(header.contains("t.opm:local-xyz"), "header was: " + header);
  }

  @Test
  @DisplayName("update overrides any previously extracted OPM (Datadog)")
  void updateOverridesExtractedDatadog() {
    PropagationTags tags = factory.fromHeaderValue(DATADOG, "_dd.p.opm=upstream-abc");
    tags.updateOrgPropagationMarker("local-xyz");
    assertEquals("local-xyz", tags.getOrgPropagationMarker().toString());
    String header = tags.headerValue(DATADOG);
    assertNotNull(header);
    assertTrue(header.contains("_dd.p.opm=local-xyz"), "header was: " + header);
  }

  @Test
  @DisplayName("update with null clears the OPM")
  void updateWithNullClears() {
    PropagationTags tags = factory.fromHeaderValue(W3C, "dd=t.opm:abc");
    tags.updateOrgPropagationMarker(null);
    assertNull(tags.getOrgPropagationMarker());
    String header = tags.headerValue(W3C);
    if (header != null) {
      assertFalse(header.contains("t.opm"), "header still had t.opm: " + header);
    }
  }

  @Test
  @DisplayName("emptyW3C preserves non-dd vendor tracestate sections and drops dd content")
  void emptyW3CPreservesNonDdVendors() {
    String original = "dd=s:1;o:foo;t.dm:-4;t.opm:upstream-abc,vendor1=abc,vendor2=def";
    PropagationTags stripped = factory.emptyW3C(original);
    assertNull(stripped.getOrgPropagationMarker());
    String reEncoded = stripped.headerValue(W3C);
    assertNotNull(reEncoded);
    assertFalse(reEncoded.contains("dd="), "should drop dd member but was: " + reEncoded);
    assertTrue(reEncoded.contains("vendor1=abc"), "vendor1 missing: " + reEncoded);
    assertTrue(reEncoded.contains("vendor2=def"), "vendor2 missing: " + reEncoded);
  }

  @Test
  @DisplayName("emptyW3C with null tracestate behaves like empty()")
  void emptyW3CNullTracestate() {
    PropagationTags stripped = factory.emptyW3C(null);
    assertNull(stripped.getOrgPropagationMarker());
    assertNull(stripped.headerValue(W3C));
  }

  @Test
  @DisplayName("emptyW3C with empty string tracestate behaves like empty()")
  void emptyW3CEmptyTracestate() {
    PropagationTags stripped = factory.emptyW3C("");
    assertNull(stripped.getOrgPropagationMarker());
    assertNull(stripped.headerValue(W3C));
  }

  @Test
  @DisplayName("empty tags can have an OPM stamped on them and serialize it")
  void emptyTagsCanReceiveOpm() {
    PropagationTags tags = factory.empty();
    assertNull(tags.getOrgPropagationMarker());
    tags.updateOrgPropagationMarker("local-xyz");
    assertEquals("local-xyz", tags.getOrgPropagationMarker().toString());
    String datadog = tags.headerValue(DATADOG);
    assertNotNull(datadog);
    assertTrue(datadog.contains("_dd.p.opm=local-xyz"), "datadog header: " + datadog);
    String w3c = tags.headerValue(W3C);
    assertNotNull(w3c);
    assertTrue(w3c.contains("t.opm:local-xyz"), "w3c header: " + w3c);
  }
}
