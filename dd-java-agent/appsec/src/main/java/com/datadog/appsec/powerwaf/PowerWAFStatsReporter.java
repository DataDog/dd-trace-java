package com.datadog.appsec.powerwaf;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import datadog.trace.api.TraceSegment;
import io.sqreen.powerwaf.PowerwafMetrics;
import java.util.Collection;

public class PowerWAFStatsReporter implements TraceSegmentPostProcessor {
  private static final String TOTAL_DURATION_US_TAG = "_dd.appsec.waf.duration_ext";
  private static final String TOTAL_DDWAF_RUN_DURATION_US_TAG = "_dd.appsec.waf.duration";

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent100> collectedEvents) {
    PowerwafMetrics metrics = ctx.getWafMetrics();
    if (metrics != null) {
      segment.setTagTop(TOTAL_DURATION_US_TAG, metrics.getTotalRunTimeNs() / 1000L);
      segment.setTagTop(TOTAL_DDWAF_RUN_DURATION_US_TAG, metrics.getTotalDdwafRunTimeNs() / 1000L);
    }
  }
}
