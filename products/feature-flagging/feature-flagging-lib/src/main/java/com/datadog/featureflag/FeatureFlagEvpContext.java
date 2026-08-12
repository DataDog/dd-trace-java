package com.datadog.featureflag;

import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;

final class FeatureFlagEvpContext {

  private FeatureFlagEvpContext() {}

  /** The name of the SDK emitting the feature flag evaluation/exposure EVP data. */
  private static final String SOURCE_NAME = "dd-trace-java";

  static Map<String, String> from(final Config config) {
    final Map<String, String> context = new HashMap<>(6);
    context.put("service", config.getServiceName() == null ? "unknown" : config.getServiceName());
    if (config.getEnv() != null) {
      context.put("env", config.getEnv());
    }
    if (config.getVersion() != null) {
      context.put("version", config.getVersion());
    }
    // SDK identity — populates the `source.name` / `source.version` facets on the
    // `flag_evaluations` and `exposures` EVP streams. See FFL-2995.
    context.put("source.name", SOURCE_NAME);
    context.put("source.version", TracerVersion.TRACER_VERSION);
    return context;
  }
}
