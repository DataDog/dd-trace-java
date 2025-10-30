package com.datadog.featureflag.ufc.v1;

import datadog.trace.api.featureflag.FeatureFlagConfig;
import java.util.Map;

public class ServerConfiguration implements FeatureFlagConfig {
  public final String createdAt;
  public final String format;
  public final Environment environment;
  public final Map<String, Flag> flags;

  public ServerConfiguration(
      final String createdAt,
      final String format,
      final Environment environment,
      final Map<String, Flag> flags) {
    this.createdAt = createdAt;
    this.format = format;
    this.environment = environment;
    this.flags = flags;
  }
}
