package datadog.smoketest.controller;

import javax.servlet.http.Cookie;

public class CookieTestSuite {

  private Cookie cookie;

  public CookieTestSuite(
      final String name,
      final String value,
      final String comment,
      final String domain,
      final String path) {
    cookie = new Cookie(name, value);
    cookie.setComment(comment);
    cookie.setDomain(domain);
    cookie.setPath(path);
  }

  public String getName() {
    return cookie.getName();
  }

  public String getValue() {
    return cookie.getValue();
  }

  public String getComment() {
    return cookie.getComment();
  }

  public String getDomain() {
    return cookie.getDomain();
  }

  public String getPath() {
    return cookie.getPath();
  }

  public Cookie getCookie() {
    return cookie;
  }
}
