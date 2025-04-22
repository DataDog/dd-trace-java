package com.datadog.appsec.ddwaf;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.ddwaf.WafMetrics;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;

public class WAFStatsReporter implements TraceSegmentPostProcessor {
  private static final String WAF_TOTAL_DURATION_US_TAG = "_dd.appsec.waf.duration_ext";
  private static final String WAF_TOTAL_DDWAF_RUN_DURATION_US_TAG = "_dd.appsec.waf.duration";
  private static final String RASP_TOTAL_DURATION_US_TAG = "_dd.appsec.rasp.duration_ext";
  private static final String RASP_TOTAL_DDWAF_RUN_DURATION_US_TAG = "_dd.appsec.rasp.duration";
  private static final String RASP_RULE_EVAL = "_dd.appsec.rasp.rule.eval";
  private static final String RULE_FILE_VERSION = "_dd.appsec.event_rules.version";
  public static final String WAF_TIMEOUTS_TAG = "_dd.appsec.waf.timeouts";
  public static final String RASP_TIMEOUT_TAG = "_dd.appsec.rasp.timeout";

  // XXX: if config is updated, this may not match the actual version run during this request
  // However, as of this point, we don't update rules at runtime.
  volatile String rulesVersion;

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent> collectedEvents) {
    WafMetrics wafMetrics = ctx.getWafMetrics();
    if (wafMetrics != null) {
      segment.setTagTop(WAF_TOTAL_DURATION_US_TAG, wafMetrics.getTotalRunTimeNs() / 1000L);
      segment.setTagTop(
          WAF_TOTAL_DDWAF_RUN_DURATION_US_TAG, wafMetrics.getTotalDdwafRunTimeNs() / 1000L);
    }

    WafMetrics raspMetrics = ctx.getRaspMetrics();
    if (raspMetrics != null) {
      segment.setTagTop(RASP_TOTAL_DURATION_US_TAG, raspMetrics.getTotalRunTimeNs() / 1000L);
      segment.setTagTop(
          RASP_TOTAL_DDWAF_RUN_DURATION_US_TAG, raspMetrics.getTotalDdwafRunTimeNs() / 1000L);
      final int raspCount = ctx.getRaspMetricsCounter().get();
      if (raspCount > 0) {
        segment.setTagTop(RASP_RULE_EVAL, raspCount);
      }
    }

    String rulesVersion = this.rulesVersion;
    if (rulesVersion != null) {
      segment.setTagTop(RULE_FILE_VERSION, rulesVersion);
    }

    if (ctx.getWafTimeouts() > 0) {
      segment.setTagTop(WAF_TIMEOUTS_TAG, ctx.getWafTimeouts());
    }

    if (ctx.getRaspTimeouts() > 0) {
      segment.setTagTop(RASP_TIMEOUT_TAG, ctx.getRaspTimeouts());
    }
  }
}
