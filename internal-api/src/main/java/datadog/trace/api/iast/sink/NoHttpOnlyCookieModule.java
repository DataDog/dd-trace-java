package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.net.HttpCookie;
import java.util.List;

public interface NoHttpOnlyCookieModule extends IastModule {
  public void onCookies(List<HttpCookie> cookies);

  public void onCookie(String cookieName, boolean isHtmlOnly);
}
