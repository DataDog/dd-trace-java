package com.datadog.iast.sink;

import com.datadog.iast.util.CookieSecurityInfo;

public interface ForCookieSecurityInfo {
  public void onCookie(CookieSecurityInfo cookieSecurityInfo);
}
