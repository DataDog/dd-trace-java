package datadog.trace.core.taginterceptor;

import datadog.trace.api.Config;

public class RuleFlags {

  public enum Feature {
    // These names all derive from the simple class names which
    // were exposed as config at some point in the past.
    RESOURCE_NAME("ResourceNameRule"),
    URL_AS_RESOURCE_NAME("URLAsResourceNameRule"),
    STATUS_404("Status404Rule"),
    STATUS_404_DECORATOR("Status404Decorator"),
    DB_STATEMENT("DBStatementRule"),
    FORCE_MANUAL_DROP("ForceManualDropTagInterceptor"),
    FORCE_MANUAL_KEEP("ForceManualKeepTagInterceptor"),
    FORCE_SAMPLING_PRIORITY("ForceSamplingPriorityTagInterceptor"),
    PEER_SERVICE("PeerServiceTagInterceptor", false),
    SERVICE_NAME("ServiceNameTagInterceptor"),
    SERVLET_CONTEXT("ServletContextTagInterceptor");

    private final String name;

    private final boolean defaultEnabled;

    Feature(String name) {
      this.name = name;
      this.defaultEnabled = true;
    }

    Feature(String name, boolean defaultEnabled) {
      this.name = name;
      this.defaultEnabled = defaultEnabled;
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
      if (config.isRuleEnabled(feature.name, feature.defaultEnabled)) {
        flags[feature.ordinal()] = true;
      }
    }
  }

  public boolean isEnabled(Feature feature) {
    return flags[feature.ordinal()];
  }
}
