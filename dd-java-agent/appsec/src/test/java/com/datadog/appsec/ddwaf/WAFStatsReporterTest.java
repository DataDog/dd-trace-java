package com.datadog.appsec.ddwaf;

import static java.util.Collections.emptyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WAFStatsReporterTest extends DDJavaSpecification {

  private final WAFStatsReporter reporter = new WAFStatsReporter();
  private final AppSecRequestContext ctx = mock(AppSecRequestContext.class);

  @Test
  void reporterReportsWafTimingsAndVersion() {
    // setup
    FixedWafMetrics metrics = new FixedWafMetrics(1_000, 2_000);
    TraceSegment segment = mock(TraceSegment.class);
    reporter.setRulesVersion("1.2.3");
    int wafTimeouts = 1;

    // and
    when(ctx.getWafTimeouts()).thenReturn(wafTimeouts);
    when(ctx.getWafMetrics()).thenReturn(metrics);

    // when
    reporter.processTraceSegment(segment, ctx, emptyList());

    // then
    verify(ctx).getWafMetrics();
    verify(segment).setTagTop("_dd.appsec.waf.duration", 1L);
    verify(segment).setTagTop("_dd.appsec.waf.duration_ext", 2L);
    verify(segment).setTagTop("_dd.appsec.event_rules.version", "1.2.3");
    verify(segment).setTagTop("_dd.appsec.waf.timeouts", wafTimeouts);
  }

  @Test
  void reporterReportsRaspTimingsAndVersion() {
    // setup
    FixedWafMetrics raspMetrics = new FixedWafMetrics(3_000, 4_000);
    TraceSegment segment = mock(TraceSegment.class);
    reporter.setRulesVersion("1.2.3");
    int raspTimeouts = 1;

    // and
    when(ctx.getRaspTimeouts()).thenReturn(raspTimeouts);
    when(ctx.getWafMetrics()).thenReturn(null);
    when(ctx.getRaspMetrics()).thenReturn(raspMetrics);
    when(ctx.getRaspMetricsCounter()).thenReturn(new AtomicInteger(5));

    // when
    reporter.processTraceSegment(segment, ctx, emptyList());

    // then
    verify(ctx).getWafMetrics();
    verify(ctx).getRaspMetrics();
    verify(ctx).getRaspMetricsCounter();
    verify(segment).setTagTop("_dd.appsec.rasp.duration", 3L);
    verify(segment).setTagTop("_dd.appsec.rasp.duration_ext", 4L);
    verify(segment).setTagTop("_dd.appsec.rasp.rule.eval", 5);
    verify(segment).setTagTop("_dd.appsec.event_rules.version", "1.2.3");
    verify(segment).setTagTop("_dd.appsec.rasp.timeout", raspTimeouts);
  }

  @Test
  void reportsNothingIfMetricsAreNull() {
    // setup
    TraceSegment segment = mock(TraceSegment.class);
    when(ctx.getWafMetrics()).thenReturn(null);

    // when
    reporter.processTraceSegment(segment, ctx, emptyList());

    // then
    verify(ctx).getWafMetrics();
    verifyNoInteractions(segment);
  }
}
