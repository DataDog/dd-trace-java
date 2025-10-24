package com.datadog.featureflag.ufc.v1;

import java.util.Map;

public class ServerConfiguration {
  public final String createdAt;
  public final String format;
  public final Environment environment;
  public final Map<String, Flag> flags;

  public ServerConfiguration(
      String createdAt, String format, Environment environment, Map<String, Flag> flags) {
    this.createdAt = createdAt;
    this.format = format;
    this.environment = environment;
    this.flags = flags;
  }
}
