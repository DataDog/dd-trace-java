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
    // SDK identity (source.name / source.version) is emitted per-event at the top level
    // (sibling of flag/variant/targeting_key), matching the flagevaluation track schema in
    // logs-backend. Putting it in the batch context would map it to context.source.*, which is
    // not a declared facet and causes the indexer to drop the event.
    return context;
  }
}
