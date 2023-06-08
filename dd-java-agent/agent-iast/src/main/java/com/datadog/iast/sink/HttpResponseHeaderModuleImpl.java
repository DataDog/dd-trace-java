package com.datadog.iast.sink;

import com.datadog.iast.util.CookieSecurityInfo;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nonnull;

public class HttpResponseHeaderModuleImpl implements HttpResponseHeaderModule {
  private static final String SET_COOKIE_HEADER = "Set-Cookie";
  private final List<ForHeader> forHeaders = new LinkedList<>();
  private final List<ForCookieSecurityInfo> forCookies = new LinkedList<>();

  @Override
  public void clearDelegates() {
    forHeaders.clear();
    forCookies.clear();
  }

  @Override
  public void addDelegate(@Nonnull final ForHeader delegate) {
    forHeaders.add(delegate);
  }

  @Override
  public void addDelegate(@Nonnull final ForCookie delegate) {
    forCookies.add((ForCookieSecurityInfo) delegate);
  }

  @Override
  public void onHeader(@Nonnull final String name, final String value) {
    if (!forCookies.isEmpty() && SET_COOKIE_HEADER.equalsIgnoreCase(name)) {
      CookieSecurityInfo cookieSecurityInfo = new CookieSecurityInfo(value);
      onCookie(cookieSecurityInfo);
    }
    for (final ForHeader headerHandler : forHeaders) {
      headerHandler.onHeader(name, value);
    }
  }

  @Override
  public void onCookie(
      String cookieName, boolean isSecure, boolean isHttpOnly, Boolean isSameSiteStrict) {
    CookieSecurityInfo cookieSecurityInfo =
        new CookieSecurityInfo(cookieName, isSecure, isHttpOnly, isSameSiteStrict);
    onCookie(cookieSecurityInfo);
  }

  private void onCookie(CookieSecurityInfo cookieSecurityInfo) {
    for (ForCookieSecurityInfo cookieHandler : forCookies) {
      cookieHandler.onCookie(cookieSecurityInfo);
    }
  }
}
