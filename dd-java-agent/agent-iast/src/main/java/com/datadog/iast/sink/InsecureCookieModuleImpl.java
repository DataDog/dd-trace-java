package com.datadog.iast.sink;

import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public class InsecureCookieModuleImpl implements InsecureCookieModule<VulnerabilityType> {

  private static final int EXPIRES_YEAR_LIMIT = 2000;

  @Override
  public boolean isVulnerable(@Nonnull final Cookie cookie) {
    return !cookie.isSecure() && !cookieValueIsEmpty(cookie.getCookieValue()) && !expired(cookie);
  }

  private boolean cookieValueIsEmpty(final String cookieValue) {
    return cookieValue == null || cookieValue.isEmpty();
  }

  private boolean expired(final Cookie cookie) {
    if (cookie.getMaxAge() != null && cookie.getMaxAge() == 0) {
      return true;
    }
    if (cookie.getExpiresYear() != null && cookie.getExpiresYear() < EXPIRES_YEAR_LIMIT) {
      return true;
    }
    return false;
  }

  @Override
  public VulnerabilityType getType() {
    return VulnerabilityType.INSECURE_COOKIE;
  }
}
