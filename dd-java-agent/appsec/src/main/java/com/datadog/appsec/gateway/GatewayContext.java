package com.datadog.appsec.gateway;

import datadog.trace.api.telemetry.RuleType;

public class GatewayContext {
  public final boolean isTransient;
  public final boolean isRasp;

  public final RuleType raspRuleType;

  public GatewayContext(final boolean isTransient) {
    this(isTransient, null);
  }

  public GatewayContext(final boolean isTransient, final RuleType raspRuleType) {
    this.isTransient = isTransient;
    this.isRasp = raspRuleType != null;
    this.raspRuleType = raspRuleType;
  }
}
