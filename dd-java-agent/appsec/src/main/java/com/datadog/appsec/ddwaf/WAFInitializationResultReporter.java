package com.datadog.appsec.ddwaf;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafDiagnostics;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class WAFInitializationResultReporter implements TraceSegmentPostProcessor {
  private static final String WAF_VERSION = "_dd.appsec.waf.version";
  private static final String RULE_ERRORS = "_dd.appsec.event_rules.errors";
  private static final String RULES_LOADED = "_dd.appsec.event_rules.loaded";
  private static final String RULE_ERROR_COUNT = "_dd.appsec.event_rules.error_count";

  private static final JsonAdapter<Map<String, List<String>>> RULES_ERRORS_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(
              Types.newParameterizedType(
                  Map.class, String.class, Types.newParameterizedType(List.class, String.class)));
  private final AtomicReference<WafDiagnostics> pendingReportRef = new AtomicReference<>();

  public void setReportForPublication(WafDiagnostics report) {
    this.pendingReportRef.set(report);
  }

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent> collectedEvents) {
    WafDiagnostics report = pendingReportRef.get();
    if (report == null) {
      return;
    }

    if (!pendingReportRef.compareAndSet(report, null)) {
      return;
    }

    segment.setTagTop(RULE_ERRORS, RULES_ERRORS_ADAPTER.toJson(report.getAllErrors()));
    segment.setTagTop(RULES_LOADED, report.getNumConfigOK());
    segment.setTagTop(RULE_ERROR_COUNT, report.getNumConfigError());
    segment.setTagTop(WAF_VERSION, Waf.LIB_VERSION);

    segment.setTagTop(Tags.ASM_KEEP, true);
  }
}
