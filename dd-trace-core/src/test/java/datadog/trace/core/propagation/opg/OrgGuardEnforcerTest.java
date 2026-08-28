package datadog.trace.core.propagation.opg;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OrgGuardEnforcer truth table")
class OrgGuardEnforcerTest {

  private PropagationTags.Factory factory;
  private HealthMetrics healthMetrics;

  @BeforeEach
  void setUp() {
    factory = PropagationTags.factory();
    healthMetrics = mock(HealthMetrics.class);
  }

  @Test
  @DisplayName("local OPM unknown -> no enforcement")
  void localUnknown() {
    OrgGuardEnforcer enforcer = enforcer(false, emptySet(), () -> null);
    ExtractedContext ctx = ctxWithOpm("X");
    assertSame(ctx, enforcer.enforce(ctx));
    verify(healthMetrics, never()).onOrgGuardEnforce(any(OrgGuard.Reason.class));
  }

  @Test
  @DisplayName("lax: inbound OPM missing -> no enforcement")
  void laxInboundMissing() {
    OrgGuardEnforcer enforcer = enforcer(false, emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm(null);
    assertSame(ctx, enforcer.enforce(ctx));
    verify(healthMetrics, never()).onOrgGuardEnforce(any(OrgGuard.Reason.class));
  }

  @Test
  @DisplayName("strict: inbound OPM missing -> strip with strict_missing")
  void strictInboundMissing() {
    OrgGuardEnforcer enforcer = enforcer(true, emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm(null, /*samplingPriority*/ 2, "synthetics");
    TagContext result = enforcer.enforce(ctx);
    assertNotSame(ctx, result);
    assertStripped((ExtractedContext) result, ctx);
    verify(healthMetrics, times(1)).onOrgGuardEnforce(OrgGuard.Reason.STRICT_MISSING);
  }

  @Test
  @DisplayName("inbound OPM matches local -> no enforcement")
  void match() {
    OrgGuardEnforcer enforcer = enforcer(false, emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("L");
    assertSame(ctx, enforcer.enforce(ctx));
    verify(healthMetrics, never()).onOrgGuardEnforce(any(OrgGuard.Reason.class));
  }

  @Test
  @DisplayName("inbound OPM is in trusted list -> no enforcement")
  void trusted() {
    Set<String> trusted = new HashSet<>();
    trusted.add("X");
    trusted.add("Y");
    OrgGuardEnforcer enforcer = enforcer(false, trusted, () -> "L");
    ExtractedContext ctx = ctxWithOpm("X");
    assertSame(ctx, enforcer.enforce(ctx));
    verify(healthMetrics, never()).onOrgGuardEnforce(any(OrgGuard.Reason.class));
  }

  @Test
  @DisplayName("lax: inbound != local -> strip with mismatch")
  void mismatchLax() {
    OrgGuardEnforcer enforcer = enforcer(false, emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("X", 2, "synthetics");
    TagContext result = enforcer.enforce(ctx);
    assertNotSame(ctx, result);
    assertStripped((ExtractedContext) result, ctx);
    verify(healthMetrics, times(1)).onOrgGuardEnforce(OrgGuard.Reason.MISMATCH);
  }

  @Test
  @DisplayName("strict: inbound != local -> strip with mismatch")
  void mismatchStrict() {
    OrgGuardEnforcer enforcer = enforcer(true, emptySet(), () -> "L");
    ExtractedContext ctx = ctxWithOpm("X", 2, "synthetics");
    TagContext result = enforcer.enforce(ctx);
    assertNotSame(ctx, result);
    assertStripped((ExtractedContext) result, ctx);
    verify(healthMetrics, times(1)).onOrgGuardEnforce(OrgGuard.Reason.MISMATCH);
  }

  @Test
  @DisplayName("partial TagContext (not ExtractedContext) -> always pass through")
  void partialContext() {
    OrgGuardEnforcer enforcer = enforcer(true, emptySet(), () -> "L");
    TagContext partial = new TagContext("upstream", null);
    assertSame(partial, enforcer.enforce(partial));
    verify(healthMetrics, never()).onOrgGuardEnforce(any(OrgGuard.Reason.class));
  }

  @Test
  @DisplayName("null input is passed through")
  void nullInput() {
    OrgGuardEnforcer enforcer = enforcer(true, emptySet(), () -> "L");
    assertNull(enforcer.enforce(null));
    verify(healthMetrics, never()).onOrgGuardEnforce(any(OrgGuard.Reason.class));
  }

  @Test
  @DisplayName("strip preserves W3C non-dd vendor tracestate sections")
  void stripPreservesNonDdVendors() {
    OrgGuardEnforcer enforcer = enforcer(false, emptySet(), () -> "L");
    PropagationTags tags =
        factory.fromHeaderValue(W3C, "dd=s:1;o:foo;t.opm:upstream-X,vendor1=abc,vendor2=def");
    ExtractedContext ctx =
        new ExtractedContext(
            DDTraceId.from(123L), 456L, 2, "origin", tags, TracePropagationStyle.TRACECONTEXT);
    TagContext result = enforcer.enforce(ctx);
    assertNotSame(ctx, result);
    ExtractedContext stripped = (ExtractedContext) result;
    String reEncoded = stripped.getPropagationTags().headerValue(W3C);
    assertNotNull(reEncoded);
    assertFalse(reEncoded.contains("dd="), "dd= should be dropped: " + reEncoded);
    assertTrue(reEncoded.contains("vendor1=abc"), "vendor1 missing: " + reEncoded);
    assertTrue(reEncoded.contains("vendor2=def"), "vendor2 missing: " + reEncoded);
  }

  // ---- helpers ----

  private OrgGuardEnforcer enforcer(
      boolean strict, Set<String> trusted, Supplier<String> localOpmSupplier) {
    return new OrgGuardEnforcer(strict, trusted, localOpmSupplier, factory, healthMetrics);
  }

  private ExtractedContext ctxWithOpm(String opm) {
    return ctxWithOpm(opm, SAMPLER_KEEP, "origin");
  }

  private ExtractedContext ctxWithOpm(String opm, int samplingPriority, String origin) {
    PropagationTags tags = factory.empty();
    if (opm != null) {
      tags.updateOrgPropagationMarker(opm);
    }
    tags.updateTraceSamplingPriority(samplingPriority, MANUAL);
    tags.updateTraceOrigin(origin);
    return new ExtractedContext(
        DDTraceId.from(123L), 456L, samplingPriority, origin, tags, DATADOG);
  }

  private static void assertStripped(ExtractedContext stripped, ExtractedContext original) {
    assertEquals(original.getTraceId(), stripped.getTraceId());
    assertEquals(original.getSpanId(), stripped.getSpanId());
    assertEquals(UNSET, stripped.getSamplingPriority());
    assertNull(stripped.getOrigin());
    assertNull(stripped.getPropagationTags().getOrgPropagationMarker());
    assertNull(stripped.getPropagationTags().getOrigin());
    assertEquals(UNSET, stripped.getPropagationTags().getSamplingPriority());
  }
}
