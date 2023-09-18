package com.datadog.iast.sink;

import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.NoSameSiteCookieModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public class NoSameSiteCookieModuleImpl implements NoSameSiteCookieModule<VulnerabilityType> {

  private static final String STRICT_VALUE = "Strict";

  @Override
  public boolean isVulnerable(@Nonnull final Cookie cookie) {
    return !STRICT_VALUE.equalsIgnoreCase(cookie.getSameSite());
  }

  @Override
  public VulnerabilityType getType() {
    return VulnerabilityType.NO_SAMESITE_COOKIE;
  }
}
