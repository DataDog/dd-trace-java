package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class WellKnownTags {

  private final UTF8BytesString hostname;
  private final UTF8BytesString env;
  private final UTF8BytesString service;
  private final UTF8BytesString version;

  public WellKnownTags(
      CharSequence hostname, CharSequence env, CharSequence service, CharSequence version) {
    this.hostname = UTF8BytesString.create(hostname);
    this.env = UTF8BytesString.create(env);
    this.service = UTF8BytesString.create(service);
    this.version = UTF8BytesString.create(version);
  }

  public UTF8BytesString getHostname() {
    return hostname;
  }

  public UTF8BytesString getEnv() {
    return env;
  }

  public UTF8BytesString getService() {
    return service;
  }

  public UTF8BytesString getVersion() {
    return version;
  }
}
