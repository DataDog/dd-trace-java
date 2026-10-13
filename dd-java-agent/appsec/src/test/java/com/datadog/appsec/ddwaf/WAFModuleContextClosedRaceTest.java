package com.datadog.appsec.ddwaf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.appsec.config.AppSecModuleConfigurer;
import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.datadog.ddwaf.WafHandle;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.telemetry.RuleType;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code WAFModule.WAFDataCallback.onDataAvailable} null-skip branch exercised when
 * {@code doRunWaf} returns {@code null} because the {@code WafContext} was closed concurrently
 * between the {@code isWafContextClosed()} fast-path check and context creation (APPSEC-69085).
 */
class WAFModuleContextClosedRaceTest {

  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private WafBuilder wafBuilder;
  private WAFModule wafModule;
  private DataListener dataListener;

  @BeforeEach
  void setup() throws Exception {
    assertTrue(WafInitialization.ONLINE, "libddwaf must be available for this test");
    Waf.initialize(false);
    wafBuilder = new WafBuilder();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("test_multi_config.json")) {
      wafBuilder.addOrUpdateConfig("test", ADAPTER.fromJson(Okio.buffer(Okio.source(stream))));
    }

    wafModule = new WAFModule();
    wafModule.setWafBuilder(wafBuilder);
    AppSecModuleConfigurer.SubconfigListener[] captured =
        new AppSecModuleConfigurer.SubconfigListener[1];
    wafModule.config(
        new AppSecModuleConfigurer() {
          @Override
          public void addSubConfigListener(
              String key, AppSecModuleConfigurer.SubconfigListener listener) {
            captured[0] = listener;
          }

          @Override
          public void addTraceSegmentPostProcessor(TraceSegmentPostProcessor interceptor) {}
        });
    captured[0].onNewSubconfig(null, AppSecModuleConfigurer.Reconfiguration.NOOP);
    dataListener = wafModule.getDataSubscriptions().iterator().next();
  }

  @AfterEach
  void tearDown() {
    if (wafBuilder != null) {
      wafBuilder.close();
    }
  }

  @Test
  void skipsAndIncrementsCounterWhenContextClosedConcurrently() {
    AppSecRequestContext reqCtx = mock(AppSecRequestContext.class);
    when(reqCtx.isWafContextClosed()).thenReturn(false);
    when(reqCtx.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(null);

    ChangeableFlow flow = new ChangeableFlow();
    GatewayContext gwCtx = new GatewayContext(false);

    dataListener.onDataAvailable(
        flow, reqCtx, MapDataBundle.ofDelegate(Collections.emptyMap()), gwCtx);

    assertFalse(flow.isBlocking());
    WafMetricCollector.get().prepareMetrics();
    boolean sawContextClosedRace =
        WafMetricCollector.get().drain().stream()
            .anyMatch(m -> "waf.context_closed_race".equals(m.metricName));
    assertTrue(sawContextClosedRace, "expected waf.context_closed_race to be reported");
  }

  @Test
  void countsRaspEvalNotSkippedWhenContextClosedConcurrently() {
    AppSecRequestContext reqCtx = mock(AppSecRequestContext.class);
    when(reqCtx.isWafContextClosed()).thenReturn(false);
    when(reqCtx.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(null);

    ChangeableFlow flow = new ChangeableFlow();
    GatewayContext gwCtx = new GatewayContext(false, RuleType.LFI);

    dataListener.onDataAvailable(
        flow, reqCtx, MapDataBundle.ofDelegate(Collections.emptyMap()), gwCtx);

    assertFalse(flow.isBlocking());
    WafMetricCollector.get().prepareMetrics();
    // The eval attempt was already counted before the race was detected; rasp.rule.skipped is
    // reserved for calls that never attempted eval (e.g. the isWafContextClosed() fast path), so
    // it must not also be reported here - otherwise the same callback is double-counted.
    Collection<WafMetricCollector.WafMetric> metrics = WafMetricCollector.get().drain();
    boolean sawRaspEval = metrics.stream().anyMatch(m -> "rasp.rule.eval".equals(m.metricName));
    boolean sawRaspSkipped =
        metrics.stream().anyMatch(m -> "rasp.rule.skipped".equals(m.metricName));
    assertTrue(sawRaspEval, "expected rasp.rule.eval to be reported");
    assertFalse(
        sawRaspSkipped, "rasp.rule.skipped must not double-count an already-evaluated call");
  }

  /**
   * Covers the structurally distinct race pointed out in review: {@code getOrCreateWafContext()}
   * can return a non-null {@link WafContext} that is closed by another thread between the fetch and
   * the actual {@code run()} call. In practice {@code closeWafContext()} flips {@code
   * wafContextClosed} atomically with closing the native context, so by the time {@code run()}
   * observes the closed context, {@code isWafContextClosed()} is already {@code true} - this
   * mirrors that ordering rather than closing the context independently of the flag. {@code run()}
   * then throws because the native context is no longer online, which {@code WAFModule} rewraps as
   * {@code UnclassifiedWafException}. That case must be treated the same as the null-return race:
   * no error log, no error-code metric, only {@code wafContextClosedRace()}.
   */
  @Test
  void skipsAndIncrementsCounterWhenContextClosedDuringRun() throws Exception {
    WafHandle wafHandle = wafBuilder.buildWafHandleInstance();
    WafContext closedContext = new WafContext(wafHandle);
    closedContext.close();

    AppSecRequestContext reqCtx = mock(AppSecRequestContext.class);
    // First call is the fast-path check before doRunWaf() runs (must be false to reach run());
    // subsequent calls mimic closeWafContext() flipping the flag concurrently while run() fails.
    when(reqCtx.isWafContextClosed()).thenReturn(false, true);
    when(reqCtx.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(closedContext);

    ChangeableFlow flow = new ChangeableFlow();
    GatewayContext gwCtx = new GatewayContext(false);

    dataListener.onDataAvailable(
        flow, reqCtx, MapDataBundle.ofDelegate(Collections.emptyMap()), gwCtx);

    assertFalse(flow.isBlocking());
    WafMetricCollector.get().prepareMetrics();
    Collection<WafMetricCollector.WafMetric> metrics = WafMetricCollector.get().drain();
    boolean sawContextClosedRace =
        metrics.stream().anyMatch(m -> "waf.context_closed_race".equals(m.metricName));
    boolean sawErrorCode = metrics.stream().anyMatch(m -> "waf.error".equals(m.metricName));
    assertTrue(sawContextClosedRace, "expected waf.context_closed_race to be reported");
    assertFalse(
        sawErrorCode,
        "a benign context-closed race must not be double-counted as a real WAF error");
  }
}
