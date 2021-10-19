package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.attack.Attack010;
import com.datadog.appsec.report.raw.events.attack.InbandAppSec;
import com.datadog.appsec.report.raw.events.attack.InbandTrigger;
import com.datadog.appsec.report.raw.events.attack._definitions.rule.Rule010;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.TraceSegment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class InbandReportServiceImpl implements InbandReportService {
  @Override
  public void reportAttacks(Collection<Attack010> attacks, TraceSegment traceSegment) {
    if (attacks.isEmpty()) {
      return;
    }
    List<InbandTrigger> triggers = new ArrayList<>(attacks.size());
    List<String> types = new ArrayList<>(attacks.size());
    List<String> ruleIds = new ArrayList<>(attacks.size());
    for (Attack010 attack : attacks) {
      convertAttack(attack, triggers, types, ruleIds);
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

  private static void convertAttack(
      Attack010 attack,
      Collection<InbandTrigger> triggers,
      Collection<String> types,
      Collection<String> ruleIds) {
    Rule010 rule = attack.getRule();
    triggers.add(
        new InbandTrigger.InbandTriggerBuilder()
            .withRule(rule)
            .withRuleMatch(attack.getRuleMatch())
            .build());
    types.add(attack.getType());
    ruleIds.add(rule.getId());
  }
}
