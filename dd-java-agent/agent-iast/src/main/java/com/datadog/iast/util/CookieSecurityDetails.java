package com.datadog.iast.util;

public class CookieSecurityDetails {
  private String cookieName;
  private boolean isSecure = false;
  private boolean isSameSiteStrict = false;
  private boolean isHttpOnly = false;

  public CookieSecurityDetails() {}

  public CookieSecurityDetails(
      String cookieName, boolean isSecure, boolean isHttpOnly, boolean isSameSiteStrict) {
    this.cookieName = cookieName;
    this.isSecure = isSecure;
    this.isSameSiteStrict = isSameSiteStrict;
    this.isHttpOnly = isHttpOnly;
  }

  void addAttribute(String name, String value) {
    if ("SECURE".equalsIgnoreCase(name)) {
      isSecure = true;
    }
    if ("HTTPONLY".equalsIgnoreCase(name) && "true".equalsIgnoreCase(value)) {
      isHttpOnly = true;
    }
    if ("SAMESITE".equalsIgnoreCase(name) && "strict".equalsIgnoreCase(value)) {
      isSameSiteStrict = true;
    }
  }

  public void setCookieName(String cookieName) {
    this.cookieName = cookieName;
  }

  public String getCookieName() {
    return cookieName;
  }

  public boolean isSecure() {
    return isSecure;
  }

  public boolean isSameSiteStrict() {
    return isSameSiteStrict;
  }

  public boolean isHttpOnly() {
    return isHttpOnly;
  }
}
