package com.datadog.iast.sink;

import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public class InsecureCookieModuleImpl implements InsecureCookieModule<VulnerabilityType> {

  @Override
  public boolean isVulnerable(@Nonnull final Cookie cookie) {
    return !cookieValueIsEmpty(cookie.getCookieValue()) && !cookie.isSecure();
  }

  private boolean cookieValueIsEmpty(final String cookieValue) {
    return cookieValue == null || cookieValue.isEmpty();
  }

  @Override
  public VulnerabilityType getType() {
    return VulnerabilityType.INSECURE_COOKIE;
  }
}
