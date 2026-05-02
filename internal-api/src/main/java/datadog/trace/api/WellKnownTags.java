package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class WellKnownTags {

  private final UTF8BytesString runtimeId;
  private final UTF8BytesString hostname;
  private final UTF8BytesString env;
  private final UTF8BytesString service;
  private final UTF8BytesString version;
  private final UTF8BytesString language;

  public WellKnownTags(
      CharSequence runtimeId,
      CharSequence hostname,
      CharSequence env,
      CharSequence service,
      CharSequence version,
      CharSequence language) {
    this.runtimeId = UTF8BytesString.create(runtimeId);
    this.hostname = UTF8BytesString.create(hostname);
    this.env = UTF8BytesString.create(env);
    this.service = UTF8BytesString.create(service);
    this.version = UTF8BytesString.create(version);
    this.language = UTF8BytesString.create(language);
  }

  public UTF8BytesString getRuntimeId() {
    return runtimeId;
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

  public UTF8BytesString getLanguage() {
    return language;
  }

  public String toString() {
    return "WellKnownTags{"
        + "runtimeId="
        + runtimeId
        + ", hostname="
        + hostname
        + ", env="
        + env
        + ", service="
        + service
        + ", version="
        + version
        + ", language="
        + language
        + "}";
  }
}
