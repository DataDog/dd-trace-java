package datadog.trace.core.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("OrgGuardEnforcer truth table")
class OrgGuardEnforcerTest {

  private PropagationTags.Factory factory;
  private HealthMetrics healthMetrics;

  @BeforeEach
  void setUp() {
    factory = PropagationTags.factory();
    healthMetrics = Mockito.mock(HealthMetrics.class);
  }

  @Test
  @DisplayName("disabled: never enforce, even on mismatch")
  void disabled() {
    OrgGuardEnforcer enforcer = enforcer(false, false, Collections.emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("X");
    assertSame(ctx, enforcer.maybeStrip(ctx));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("enabled, lax: local OPM unknown -> no enforcement")
  void localUnknown() {
    OrgGuardEnforcer enforcer = enforcer(true, false, Collections.emptySet(), () -> null);
    ExtractedContext ctx = ctxWithOpm("X");
    assertSame(ctx, enforcer.maybeStrip(ctx));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("enabled, lax: inbound OPM missing -> no enforcement")
  void laxInboundMissing() {
    OrgGuardEnforcer enforcer = enforcer(true, false, Collections.emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm(null);
    assertSame(ctx, enforcer.maybeStrip(ctx));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("enabled, strict: inbound OPM missing -> strip with strict_missing")
  void strictInboundMissing() {
    OrgGuardEnforcer enforcer = enforcer(true, true, Collections.emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm(null, /*samplingPriority*/ 2, "synthetics");
    TagContext result = enforcer.maybeStrip(ctx);
    assertNotSame(ctx, result);
    assertStripped((ExtractedContext) result, ctx);
    Mockito.verify(healthMetrics, times(1)).onOrgGuardEnforce("strict_missing");
  }

  @Test
  @DisplayName("enabled: inbound OPM matches local -> no enforcement")
  void match() {
    OrgGuardEnforcer enforcer = enforcer(true, false, Collections.emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("L");
    assertSame(ctx, enforcer.maybeStrip(ctx));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("enabled: inbound OPM is in trusted list -> no enforcement")
  void trusted() {
    Set<String> trusted = new HashSet<>();
    trusted.add("X");
    trusted.add("Y");
    OrgGuardEnforcer enforcer = enforcer(true, false, trusted, () -> "L");
    ExtractedContext ctx = ctxWithOpm("X");
    assertSame(ctx, enforcer.maybeStrip(ctx));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("enabled, lax: inbound != local -> strip with mismatch")
  void mismatchLax() {
    OrgGuardEnforcer enforcer = enforcer(true, false, Collections.emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("X", /*samplingPriority*/ 2, "synthetics");
    TagContext result = enforcer.maybeStrip(ctx);
    assertNotSame(ctx, result);
    assertStripped((ExtractedContext) result, ctx);
    Mockito.verify(healthMetrics, times(1)).onOrgGuardEnforce("mismatch");
  }

  @Test
  @DisplayName("enabled, strict: inbound != local -> strip with mismatch")
  void mismatchStrict() {
    OrgGuardEnforcer enforcer = enforcer(true, true, Collections.emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("X", /*samplingPriority*/ 2, "synthetics");
    TagContext result = enforcer.maybeStrip(ctx);
    assertNotSame(ctx, result);
    assertStripped((ExtractedContext) result, ctx);
    Mockito.verify(healthMetrics, times(1)).onOrgGuardEnforce("mismatch");
  }

  @Test
  @DisplayName("partial TagContext (not ExtractedContext) -> always pass through")
  void partialContext() {
    OrgGuardEnforcer enforcer = enforcer(true, true, Collections.emptySet(), () -> "L");
    TagContext partial = new TagContext("upstream", null);
    assertSame(partial, enforcer.maybeStrip(partial));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("null input is passed through")
  void nullInput() {
    OrgGuardEnforcer enforcer = enforcer(true, true, Collections.emptySet(), () -> "L");
    assertNull(enforcer.maybeStrip(null));
    Mockito.verify(healthMetrics, never()).onOrgGuardEnforce(Mockito.anyString());
  }

  @Test
  @DisplayName("strip preserves W3C non-dd vendor tracestate sections")
  void stripPreservesNonDdVendors() {
    OrgGuardEnforcer enforcer = enforcer(true, false, Collections.emptySet(), () -> "L");
    PropagationTags tags =
        factory.fromHeaderValue(
            PropagationTags.HeaderType.W3C,
            "dd=s:1;o:foo;t.opm:upstream-X,vendor1=abc,vendor2=def");
    ExtractedContext ctx =
        new ExtractedContext(
            DDTraceId.from(123L), 456L, 2, "origin", tags, TracePropagationStyle.TRACECONTEXT);
    TagContext result = enforcer.maybeStrip(ctx);
    assertNotSame(ctx, result);
    ExtractedContext stripped = (ExtractedContext) result;
    String reEncoded = stripped.getPropagationTags().headerValue(PropagationTags.HeaderType.W3C);
    org.junit.jupiter.api.Assertions.assertNotNull(reEncoded);
    org.junit.jupiter.api.Assertions.assertTrue(
        !reEncoded.contains("dd="), "dd= should be dropped: " + reEncoded);
    org.junit.jupiter.api.Assertions.assertTrue(
        reEncoded.contains("vendor1=abc"), "vendor1 missing: " + reEncoded);
    org.junit.jupiter.api.Assertions.assertTrue(
        reEncoded.contains("vendor2=def"), "vendor2 missing: " + reEncoded);
  }

  // ---- helpers ----

  private OrgGuardEnforcer enforcer(
      boolean enabled, boolean strict, Set<String> trusted, Supplier<String> localOpmSupplier) {
    return new OrgGuardEnforcer(enabled, strict, trusted, localOpmSupplier, factory, healthMetrics);
  }

  private ExtractedContext ctxWithOpm(String opm) {
    return ctxWithOpm(opm, PrioritySampling.SAMPLER_KEEP, "origin");
  }

  private ExtractedContext ctxWithOpm(String opm, int samplingPriority, String origin) {
    PropagationTags tags = factory.empty();
    if (opm != null) {
      tags.updateOrgPropagationMarker(opm);
    }
    tags.updateTraceSamplingPriority(samplingPriority, /* mechanism = MANUAL */ 4);
    tags.updateTraceOrigin(origin);
    return new ExtractedContext(
        DDTraceId.from(123L), 456L, samplingPriority, origin, tags, TracePropagationStyle.DATADOG);
  }

  private static void assertStripped(ExtractedContext stripped, ExtractedContext original) {
    assertEquals(original.getTraceId(), stripped.getTraceId());
    assertEquals(original.getSpanId(), stripped.getSpanId());
    assertEquals(PrioritySampling.UNSET, stripped.getSamplingPriority());
    assertNull(stripped.getOrigin());
    assertNull(stripped.getPropagationTags().getOrgPropagationMarker());
    assertNull(stripped.getPropagationTags().getOrigin());
    org.junit.jupiter.api.Assertions.assertEquals(
        PrioritySampling.UNSET, stripped.getPropagationTags().getSamplingPriority());
  }
}
