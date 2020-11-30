package datadog.trace.core.taginterceptor;

import datadog.trace.api.Config;

public class RuleFlags {

  public enum Feature {
    FORCE_MANUAL_DROP("ForceManualDropTagInterceptor"),
    FORCE_MANUAL_KEEP("ForceManualKeepTagInterceptor"),
    PEER_SERVICE("PeerServiceTagInterceptor"),
    SERVICE_NAME("ServiceNameTagInterceptor"),
    SERVLET_CONTEXT("ServletContextTagInterceptor");

    private final String name;

    Feature(String name) {
      this.name = name;
    }
  }

  private final boolean[] flags;

  public RuleFlags() {
    this(Config.get());
  }

  public RuleFlags(Config config) {
    Feature[] features = Feature.values();
    this.flags = new boolean[features.length];
    for (Feature feature : features) {
      if (config.isRuleEnabled(feature.name)) {
        flags[feature.ordinal()] = true;
      }
    }
  }

  public boolean isEnabled(Feature feature) {
    return flags[feature.ordinal()];
  }
}
