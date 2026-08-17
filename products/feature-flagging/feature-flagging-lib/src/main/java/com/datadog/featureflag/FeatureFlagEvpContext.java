package com.datadog.featureflag;

import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;

final class FeatureFlagEvpContext {

  private FeatureFlagEvpContext() {}

  static Map<String, String> from(final Config config) {
    final Map<String, String> context = new HashMap<>(4);
    context.put("service", config.getServiceName() == null ? "unknown" : config.getServiceName());
    if (config.getEnv() != null) {
      context.put("env", config.getEnv());
    }
    if (config.getVersion() != null) {
      context.put("version", config.getVersion());
    }
    return context;
  }
}
