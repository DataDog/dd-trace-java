package com.datadog.iast.sink;

import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public class InsecureCookieModuleImpl implements InsecureCookieModule<VulnerabilityType> {

  private static final long YEAR_2000_IN_MILLIS = 946681200000L; // Sat, 01 Jan 2000 00:00:00 CET

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
    if (cookie.getExpires() != null && cookie.getExpires().getTime() < YEAR_2000_IN_MILLIS) {
      return true;
    }
    return false;
  }

  @Override
  public VulnerabilityType getType() {
    return VulnerabilityType.INSECURE_COOKIE;
  }
}
