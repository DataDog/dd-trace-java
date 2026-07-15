package com.datadog.appsec.ddwaf;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.ddwaf.WafMetrics;
import datadog.crashtracking.ConfigManager;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.util.PidHelper;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WAFStatsReporterTest {

  private final WAFStatsReporter reporter = new WAFStatsReporter();
  private final AppSecRequestContext ctx = mock(AppSecRequestContext.class);

  @AfterEach
  void resetCachedCfgFile() throws ReflectiveOperationException {
    Field field = ConfigManager.class.getDeclaredField("cfgFile");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  void reporterReportsWafTimingsAndVersion() throws Exception {
    WafMetrics metrics = new WafMetrics();
    setMetric(metrics, "totalRunTimeNs", 2_000);
    setMetric(metrics, "totalDdwafRunTimeNs", 1_000);
    TraceSegment segment = mock(TraceSegment.class);
    reporter.setRulesVersion("1.2.3");
    int wafTimeouts = 1;

    when(ctx.getWafMetrics()).thenReturn(metrics);
    when(ctx.getWafTimeouts()).thenReturn(wafTimeouts);

    reporter.processTraceSegment(segment, ctx, Collections.emptyList());

    verify(segment).setTagTop("_dd.appsec.waf.duration", 1L);
    verify(segment).setTagTop("_dd.appsec.waf.duration_ext", 2L);
    verify(segment).setTagTop("_dd.appsec.event_rules.version", "1.2.3");
    verify(segment).setTagTop("_dd.appsec.waf.timeouts", wafTimeouts);
  }

  @Test
  void reporterReportsRaspTimingsAndVersion() throws Exception {
    WafMetrics raspMetrics = new WafMetrics();
    setMetric(raspMetrics, "totalRunTimeNs", 4_000);
    setMetric(raspMetrics, "totalDdwafRunTimeNs", 3_000);
    TraceSegment segment = mock(TraceSegment.class);
    reporter.setRulesVersion("1.2.3");
    int raspTimeouts = 1;

    when(ctx.getWafMetrics()).thenReturn(null);
    when(ctx.getRaspMetrics()).thenReturn(raspMetrics);
    when(ctx.getRaspMetricsCounter()).thenReturn(new AtomicInteger(5));
    when(ctx.getRaspTimeouts()).thenReturn(raspTimeouts);

    reporter.processTraceSegment(segment, ctx, Collections.emptyList());

    verify(segment).setTagTop("_dd.appsec.rasp.duration", 3L);
    verify(segment).setTagTop("_dd.appsec.rasp.duration_ext", 4L);
    verify(segment).setTagTop("_dd.appsec.rasp.rule.eval", 5);
    verify(segment).setTagTop("_dd.appsec.event_rules.version", "1.2.3");
    verify(segment).setTagTop("_dd.appsec.rasp.timeout", raspTimeouts);
  }

  @Test
  void reportsNothingIfMetricsAreNull() {
    TraceSegment segment = mock(TraceSegment.class);

    when(ctx.getWafMetrics()).thenReturn(null);

    reporter.processTraceSegment(segment, ctx, Collections.emptyList());

    verifyNoInteractions(segment);
  }

  @Test
  void setRulesVersionPatchesTheCrashConfigFile() throws IOException {
    File tmpDir = Files.createTempDirectory("WAFStatsReporterTest").toFile();
    tmpDir.deleteOnExit();
    File scriptFile = new File(tmpDir, "dd_crash_uploader.sh");
    ConfigManager.writeConfigToPath(scriptFile);

    reporter.setRulesVersion("4.5.6");

    File cfgFile = new File(tmpDir, "dd_crash_uploader_pid" + PidHelper.getPid() + ".cfg");
    cfgFile.deleteOnExit();
    String cfgContent = new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8);
    assertTrue(cfgContent.contains("waf_rules_version=4.5.6"));
  }

  // Reflection replicates Groovy's dynamic property access; WafMetrics has no production setter.
  private static void setMetric(WafMetrics metrics, String fieldName, long value) throws Exception {
    Field field = WafMetrics.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    ((AtomicLong) field.get(metrics)).set(value);
  }
}
