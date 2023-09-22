package com.datadog.debugger.symbol;

import java.util.List;

public class ServiceVersion {
  private final String service;

  private final String env;
  private final String version;
  private final String language;
  private final List<Scope> scopes;

  public ServiceVersion(
      String service, String env, String version, String language, List<Scope> scopes) {
    this.service = service;
    this.env = env;
    this.version = version;
    this.language = language;
    this.scopes = scopes;
  }

  public List<Scope> getScopes() {
    return scopes;
  }
}
