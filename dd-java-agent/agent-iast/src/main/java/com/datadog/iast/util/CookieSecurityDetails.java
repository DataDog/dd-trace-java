package com.datadog.iast.util;

public class CookieSecurityDetails {
  String cookieName;
  boolean isSecure = false;
  boolean isSameSiteStrict = false;
  boolean isHttpOnly = false;

  public CookieSecurityDetails() {}

  public CookieSecurityDetails(
      String cookieName, boolean isSecure, boolean isSameSiteStrict, boolean isHttpOnly) {
    this.cookieName = cookieName;
    this.isSecure = isSecure;
    this.isSameSiteStrict = isSameSiteStrict;
    this.isHttpOnly = isHttpOnly;
  }

  void addAttribute(String name, String value) {
    if ("SECURE".equalsIgnoreCase(name) && null == value) {
      isSecure = true;
    }
    if ("HTTPONLY".equalsIgnoreCase(name) && "true".equalsIgnoreCase(value)) {
      isHttpOnly = true;
    }
    if ("SAMESITE".equalsIgnoreCase(name) && "strict".equalsIgnoreCase(value)) {
      isSameSiteStrict = true;
    }
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
