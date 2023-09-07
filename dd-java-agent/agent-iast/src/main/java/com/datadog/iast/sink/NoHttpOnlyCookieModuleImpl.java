package com.datadog.iast.sink;

import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public class NoHttpOnlyCookieModuleImpl implements NoHttpOnlyCookieModule<VulnerabilityType> {

  @Override
  public boolean isVulnerable(@Nonnull final Cookie cookie) {
    return !cookie.isHttpOnly();
  }

  @Override
  public VulnerabilityType getType() {
    return VulnerabilityType.NO_HTTPONLY_COOKIE;
  }
}
