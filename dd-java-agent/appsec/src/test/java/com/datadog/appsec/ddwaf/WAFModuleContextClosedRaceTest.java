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
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.telemetry.RuleType;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.io.InputStream;
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
  void skipsRaspRuleWhenContextClosedConcurrently() {
    AppSecRequestContext reqCtx = mock(AppSecRequestContext.class);
    when(reqCtx.isWafContextClosed()).thenReturn(false);
    when(reqCtx.getOrCreateWafContext(any(), anyBoolean(), anyBoolean())).thenReturn(null);

    ChangeableFlow flow = new ChangeableFlow();
    GatewayContext gwCtx = new GatewayContext(false, RuleType.LFI);

    dataListener.onDataAvailable(
        flow, reqCtx, MapDataBundle.ofDelegate(Collections.emptyMap()), gwCtx);

    assertFalse(flow.isBlocking());
    WafMetricCollector.get().prepareMetrics();
    boolean sawRaspSkipped =
        WafMetricCollector.get().drain().stream()
            .anyMatch(m -> "rasp.rule.skipped".equals(m.metricName));
    assertTrue(sawRaspSkipped, "expected rasp.rule.skipped to be reported");
  }
}
