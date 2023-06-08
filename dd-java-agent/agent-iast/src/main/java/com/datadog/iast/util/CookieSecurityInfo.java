package com.datadog.iast.util;

import com.datadog.iast.model.Location;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

public class CookieSecurityInfo {
  private static final String SET_COOKIE = "set-cookie:";

  private String cookieName;
  private boolean isSecure = true;

  private boolean isHttpOnly = true;
  private boolean isSameSiteStrict = true;
  private AgentSpan span = null;
  Location location = null;

  public CookieSecurityInfo(
      String cookieName, boolean isSecure, boolean isHttpOnly, Boolean isSameSiteStrict) {
    this.cookieName = cookieName;
    this.isSecure = isSecure;
    this.isHttpOnly = isHttpOnly;
    this.isSameSiteStrict = isSameSiteStrict;
  }

  public CookieSecurityInfo(String cookieString) {

    List<AnalyzedCookie> cookies = new java.util.ArrayList<>();

    List<String> cookieStrings = splitMultiCookies(cookieString);
    for (String cookieStr : cookieStrings) {
      AnalyzedCookie cookie = parseInternal(cookieStr);
      cookies.add(cookie);
    }

    if (cookies.size() > 0) {
      cookieName = cookies.get(0).cookieName;
    }
    for (AnalyzedCookie analyzedCookie : cookies) {
      isSecure = isSecure && analyzedCookie.isSecure;
      isHttpOnly = isHttpOnly && analyzedCookie.isHttpOnly;
      isSameSiteStrict = isSameSiteStrict && analyzedCookie.isSameSiteStrict;
    }
  }

  public String getCookieName() {
    return cookieName;
  }

  public boolean isSecure() {
    return isSecure;
  }

  public boolean isHttpOnly() {
    return isHttpOnly;
  }

  public boolean isSameSiteStrict() {
    return isSameSiteStrict;
  }

  public Location getLocation() {
    return location;
  }

  public void setLocation(Location location) {
    this.location = location;
  }

  public AgentSpan getSpan() {
    return span;
  }

  public void setSpan(AgentSpan span) {
    this.span = span;
  }

  private AnalyzedCookie parseInternal(String header) {
    AnalyzedCookie analyzedCookie = new AnalyzedCookie();

    StringTokenizer tokenizer = new StringTokenizer(header, ";");

    // there should always have at least on name-value pair;
    // it's cookie's name
    try {
      String nameValuePair = tokenizer.nextToken();
      int index = nameValuePair.indexOf('=');
      if (index != -1) {
        analyzedCookie.cookieName = nameValuePair.substring(0, index).trim();
      } else {
        return null;
      }
    } catch (NoSuchElementException ignored) {
      return null;
    }

    // remaining name-value pairs are cookie's attributes
    while (tokenizer.hasMoreTokens()) {
      String attribute = tokenizer.nextToken();
      int index = attribute.indexOf('=');
      String name, value;
      if (index != -1) {
        name = attribute.substring(0, index).trim();
        value = attribute.substring(index + 1).trim();
      } else {
        name = attribute.trim();
        value = null;
      }
      analyzedCookie.addAttribute(name, value);
    }

    return analyzedCookie;
  }

  private static List<String> splitMultiCookies(String header) {
    List<String> cookies = new java.util.ArrayList<String>();
    int quoteCount = 0;
    int p, q;

    for (p = 0, q = 0; p < header.length(); p++) {
      char c = header.charAt(p);
      if (c == '"') quoteCount++;
      if (c == ',' && (quoteCount % 2 == 0)) {
        // it is comma and not surrounding by double-quotes
        cookies.add(header.substring(q, p));
        q = p + 1;
      }
    }

    cookies.add(header.substring(q));

    return cookies;
  }

  private class AnalyzedCookie {
    String cookieName;
    boolean isSecure = false;
    boolean isSameSiteStrict = false;

    boolean isHttpOnly = false;

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
  }
}
