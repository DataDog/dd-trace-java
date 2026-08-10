package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.TraceCollector;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.opg.OrgGuard;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Org Propagation Guard end-to-end propagator wiring")
class OrgGuardEndToEndTest {
  private PropagationTags.Factory factory;
  private HealthMetrics healthMetrics;
  private Supplier<TraceConfig> traceConfigSupplier;

  @BeforeEach
  void setUp() {
    factory = PropagationTags.factory();
    healthMetrics = mock(HealthMetrics.class);
    TraceConfig tc = mock(TraceConfig.class);
    traceConfigSupplier = () -> tc;
  }

  @Test
  @DisplayName("inject stamps the local OPM into x-datadog-tags and tracestate")
  void injectStampsLocalOpm() {
    TracingPropagator propagator = buildPropagator(true, false, Collections.emptySet(), () -> "L1");

    Map<String, String> carrier = new HashMap<>();
    Context ctx = Context.root().with(buildSpanForInjection(null));
    propagator.inject(ctx, carrier, Map::put);

    String datadogTags = carrier.get(DatadogHttpCodec.DATADOG_TAGS_KEY);
    assertNotNull(datadogTags, "x-datadog-tags missing: " + carrier);
    assertTrue(datadogTags.contains("_dd.p.opm=L1"), "datadog-tags = " + datadogTags);

    String tracestate = carrier.get("tracestate");
    assertNotNull(tracestate, "tracestate missing: " + carrier);
    assertTrue(tracestate.contains("t.opm:L1"), "tracestate = " + tracestate);
  }

  @Test
  @DisplayName("inject overrides any inherited OPM with the local one")
  void injectOverridesInheritedOpm() {
    TracingPropagator propagator = buildPropagator(true, false, Collections.emptySet(), () -> "L1");

    Map<String, String> carrier = new HashMap<>();
    Context ctx = Context.root().with(buildSpanForInjection("upstream-X"));
    propagator.inject(ctx, carrier, Map::put);

    String datadogTags = carrier.get(DatadogHttpCodec.DATADOG_TAGS_KEY);
    assertNotNull(datadogTags);
    assertTrue(datadogTags.contains("_dd.p.opm=L1"), "datadog-tags = " + datadogTags);
    assertFalse(datadogTags.contains("_dd.p.opm=upstream-X"), "datadog-tags = " + datadogTags);
  }

  @Test
  @DisplayName("extract drops dd context when OPM mismatches and enforcement is on")
  void extractStripsOnMismatch() {
    TracingPropagator propagator = buildPropagator(true, false, Collections.emptySet(), () -> "L1");

    Map<String, String> headers = new HashMap<>();
    headers.put(DatadogHttpCodec.TRACE_ID_KEY, "123");
    headers.put(DatadogHttpCodec.SPAN_ID_KEY, "456");
    headers.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "2");
    headers.put(DatadogHttpCodec.DATADOG_TAGS_KEY, "_dd.p.opm=X1,_dd.p.dm=-4");

    Context extracted = propagator.extract(Context.root(), headers, stringValuesMap());
    AgentSpan span = AgentSpan.fromContext(extracted);
    assertNotNull(span, "extracted span missing");
    ExtractedContext ec = (ExtractedContext) span.spanContext();
    assertEquals(DDTraceId.from(123L), ec.getTraceId());
    assertEquals(456L, ec.getSpanId());
    assertEquals(UNSET, ec.getSamplingPriority());
    assertNull(ec.getOrigin());
    assertNull(ec.getPropagationTags().getOrgPropagationMarker());
  }

  @Test
  @DisplayName("extract honors trusted OPMs even when they differ from the local one")
  void extractTrustedOpm() {
    Set<String> trusted = new HashSet<>();
    trusted.add("TRUSTED1");
    TracingPropagator propagator = buildPropagator(true, false, trusted, () -> "L1");

    Map<String, String> headers = new HashMap<>();
    headers.put(DatadogHttpCodec.TRACE_ID_KEY, "123");
    headers.put(DatadogHttpCodec.SPAN_ID_KEY, "456");
    headers.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "2");
    headers.put(DatadogHttpCodec.DATADOG_TAGS_KEY, "_dd.p.opm=TRUSTED1,_dd.p.dm=-4");

    Context extracted = propagator.extract(Context.root(), headers, stringValuesMap());
    ExtractedContext ec = (ExtractedContext) AgentSpan.fromContext(extracted).spanContext();
    assertEquals(2, ec.getSamplingPriority());
    assertEquals("TRUSTED1", ec.getPropagationTags().getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("extract+inject preserves non-dd tracestate vendors after stripping")
  void roundTripPreservesForeignVendors() {
    TracingPropagator propagator = buildPropagator(true, false, Collections.emptySet(), () -> "L1");

    Map<String, String> headers = new HashMap<>();
    headers.put(
        "traceparent",
        "00-0000000000000000000000000000007b-00000000000001c8-01"); // 0x7b=123, 0x1c8=456
    headers.put("tracestate", "dd=s:2;o:foo;t.opm:upstream-X;t.dm:-4,vendor1=abc,vendor2=def");

    Context extracted = propagator.extract(Context.root(), headers, stringValuesMap());
    ExtractedContext ec = (ExtractedContext) AgentSpan.fromContext(extracted).spanContext();
    assertEquals(UNSET, ec.getSamplingPriority(), "should be stripped");

    String reEncoded = ec.getPropagationTags().headerValue(W3C);
    assertNotNull(reEncoded, "re-encoded tracestate is null");
    assertTrue(reEncoded.contains("vendor1=abc"), "vendor1 missing: " + reEncoded);
    assertTrue(reEncoded.contains("vendor2=def"), "vendor2 missing: " + reEncoded);
  }

  // ---- helpers ----

  private TracingPropagator buildPropagator(
      boolean enabled, boolean strict, Set<String> trusted, Supplier<String> localOpmSupplier) {
    Config config = mock(Config.class, RETURNS_DEFAULTS);
    when(config.getxDatadogTagsMaxLength()).thenReturn(512);
    when(config.getTracePropagationStylesToExtract())
        .thenReturn(EnumSet.of(TracePropagationStyle.DATADOG, TracePropagationStyle.TRACECONTEXT));
    when(config.getTracePropagationStylesToInject())
        .thenReturn(EnumSet.of(TracePropagationStyle.DATADOG, TracePropagationStyle.TRACECONTEXT));
    when(config.isTracePropagationExtractFirst()).thenReturn(false);
    when(config.isAwsPropagationEnabled()).thenReturn(false);
    when(config.getBaggageMapping()).thenReturn(Collections.emptyMap());
    when(config.isTracePropagationStyleB3PaddingEnabled()).thenReturn(false);
    when(config.isApmTracingEnabled()).thenReturn(true);
    when(config.isTraceOrgGuardEnabled()).thenReturn(enabled);
    when(config.isTraceOrgGuardStrict()).thenReturn(strict);
    when(config.getTraceOrgGuardTrustedOpms()).thenReturn(trusted);

    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, traceConfigSupplier);
    HttpCodec.Injector injector =
        HttpCodec.createInjector(
            config, config.getTracePropagationStylesToInject(), Collections.emptyMap());
    OrgGuard orgGuard = OrgGuard.create(config, localOpmSupplier, factory, healthMetrics);
    return new TracingPropagator(
        true, orgGuard.decorateInjector(injector), orgGuard.decorateExtractor(extractor));
  }

  /**
   * Build a minimal AgentSpan wrapping a (mocked) DDSpanContext whose propagation tags can be
   * controlled by the test.
   */
  private AgentSpan buildSpanForInjection(String preExistingOpm) {
    PropagationTags tags = factory.empty();
    if (preExistingOpm != null) {
      tags.updateOrgPropagationMarker(preExistingOpm);
    }
    DDSpanContext ddCtx = mock(DDSpanContext.class);
    when(ddCtx.getTraceId()).thenReturn(DDTraceId.from(123L));
    when(ddCtx.getSpanId()).thenReturn(456L);
    when(ddCtx.getSamplingPriority()).thenReturn(2);
    when(ddCtx.lockSamplingPriority()).thenReturn(true);
    when(ddCtx.getOrigin()).thenReturn(null);
    when(ddCtx.getEndToEndStartTime()).thenReturn(0L);
    when(ddCtx.baggageItems()).thenReturn(Collections.emptySet());
    when(ddCtx.getPropagationTags()).thenReturn(tags);
    TraceCollector collector = mock(TraceCollector.class);
    when(ddCtx.getTraceCollector()).thenReturn(collector);
    return AgentSpan.fromSpanContext(ddCtx);
  }
}
