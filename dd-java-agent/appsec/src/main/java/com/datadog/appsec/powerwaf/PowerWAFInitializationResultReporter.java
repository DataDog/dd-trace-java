package com.datadog.appsec.powerwaf;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.DDTags;
import datadog.trace.api.TraceSegment;
import io.sqreen.powerwaf.RuleSetInfo;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class PowerWAFInitializationResultReporter implements TraceSegmentPostProcessor {
  private static final String RULE_FILE_VERSION = "_dd.appsec.event_rules.version";
  private static final String RULE_ERRORS = "_dd.appsec.event_rules.errors";
  private static final String RULES_LOADED = "dd.appsec.event_rules.loaded";
  private static final String RULE_ERROR_COUNT = "dd.appsec.event_rules.error_count";

  private static final JsonAdapter<Map<String, String[]>> RULES_ERRORS_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, String[].class));
  private final AtomicReference<RuleSetInfo> pendingReportRef = new AtomicReference<>();

  public void setReportForPublication(RuleSetInfo report) {
    this.pendingReportRef.set(report);
  }

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent100> collectedEvents) {
    RuleSetInfo report = pendingReportRef.get();
    if (report == null) {
      return;
    }

    if (!pendingReportRef.compareAndSet(report, null)) {
      return;
    }

    if (report.fileVersion != null) {
      segment.setTagTop(RULE_FILE_VERSION, report.fileVersion);
    }
    segment.setTagTop(RULE_ERRORS, RULES_ERRORS_ADAPTER.toJson(report.errors));
    segment.setTagTop(RULES_LOADED, report.numRulesOK);
    segment.setTagTop(RULE_ERROR_COUNT, report.numRulesError);

    segment.setTagTop(DDTags.MANUAL_KEEP, true);
  }
}
