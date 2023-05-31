package datadog.trace.api.iast.sink;

public interface InsecureCookieModule extends HttpHeaderModule {
  public void onCookie(String cookieName, boolean secure);

  public void onCookieHeader(String headerValue);
}
