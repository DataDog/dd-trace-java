package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;

public interface InsecureCookieModule extends IastModule {
  public void onCookie(String cookieName, boolean secure);

  public void onCookieHeader(String headerValue);
}
