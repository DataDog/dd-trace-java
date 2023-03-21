package datadog.smoketest.controller;

import javax.servlet.http.Cookie;

public class CookieTestSuite {

  private Cookie cookie;

  public CookieTestSuite(final String name, final String value) {
    cookie = new Cookie(name, value);
  }

  public String getName() {
    return cookie.getName();
  }

  public String getValue() {
    return cookie.getValue();
  }

  public Cookie getCookie() {
    return cookie;
  }
}
