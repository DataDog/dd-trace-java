package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.datadog.appsec.report.raw.events.InbandAppSec;
import com.datadog.appsec.report.raw.events.InbandTrigger;
import com.datadog.appsec.report.raw.events.Rule100;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.TraceSegment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class InbandReportServiceImpl implements ReportService {
  @Override
  public void reportEvents(Collection<AppSecEvent100> events, TraceSegment traceSegment) {
    if (events.isEmpty() || traceSegment == null) {
      return;
    }
    List<InbandTrigger> triggers = new ArrayList<>(events.size());
    List<String> types = new ArrayList<>(events.size());
    List<String> ruleIds = new ArrayList<>(events.size());
    for (AppSecEvent100 event : events) {
      convertEvent(event, triggers, types, ruleIds);
    }
    InbandAppSec topLevel =
        new InbandAppSec.InbandAppSecBuilder()
            .withTriggers(triggers)
            .withTypes(types)
            .withRuleIds(ruleIds)
            .build();
    traceSegment.setDataTop("appsec", new Serializer<>(topLevel, TOP_LEVEL_JSON_ADAPTER));
  }

  private static final JsonAdapter<InbandAppSec> TOP_LEVEL_JSON_ADAPTER =
      new Moshi.Builder().build().adapter(InbandAppSec.class);

  // package reachable for tests
  static final class Serializer<T> {
    private final T data;
    private final JsonAdapter<T> jsonAdapter;
    private volatile String json;

    public Serializer(T data, JsonAdapter<T> jsonAdapter) {
      this.data = data;
      this.jsonAdapter = jsonAdapter;
    }

    @Override
    public String toString() {
      try {
        String result = json;
        if (null == result) {
          result = json = jsonAdapter.toJson(data);
        }
        return result;
      } catch (Throwable ignored) {
        return "{}";
      }
    }
  }

  private static void convertEvent(
      AppSecEvent100 event,
      Collection<InbandTrigger> triggers,
      Collection<String> types,
      Collection<String> ruleIds) {
    Rule100 rule = event.getRule();
    triggers.add(
        new InbandTrigger.InbandTriggerBuilder()
            .withRule(rule)
            .withRuleMatch(event.getRuleMatch())
            .build());
    types.add(event.getEventType());
    ruleIds.add(rule.getId());
  }

  @Override
  public void close() {}
}
