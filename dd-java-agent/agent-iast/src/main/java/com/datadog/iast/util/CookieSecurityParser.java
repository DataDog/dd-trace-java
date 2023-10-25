package com.datadog.iast.util;

import static java.util.Collections.emptyList;

import datadog.trace.api.iast.util.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CookieSecurityParser {

  private static final Logger LOG = LoggerFactory.getLogger(CookieSecurityParser.class);
  private static final String SECURE_ATTR = "Secure";
  private static final String HTTP_ONLY_ATTR = "HttpOnly";
  private static final String SAME_SITE_ATTR = "SameSite";

  public static List<Cookie> parse(final String cookieString) {
    if (cookieString == null || cookieString.isEmpty()) {
      return emptyList();
    }
    try {
      final List<Cookie> cookies = new ArrayList<>();
      final int version = guessCookieVersion(cookieString);
      if (0 != version) {
        for (final String header : splitMultiCookies(cookieString)) {
          final Cookie cookie = parseInternal(header);
          if (cookie != null) {
            cookies.add(cookie);
          }
        }
      } else {
        final Cookie cookie = parseInternal(cookieString);
        if (cookie != null) {
          cookies.add(cookie);
        }
      }
      return cookies;
    } catch (final Throwable e) {
      LOG.warn("Failed to parse the cookie {}", cookieString, e);
      return emptyList();
    }
  }

  private static Cookie parseInternal(final String header) {
    String cookieName;
    boolean httpOnly = false;
    boolean secure = false;
    String sameSite = null;
    StringTokenizer tokenizer = new StringTokenizer(header, ";");

    // there should always have at least on name-value pair;
    // it's cookie's name
    try {
      String nameValuePair = tokenizer.nextToken();
      int index = nameValuePair.indexOf('=');
      if (index != -1) {
        cookieName = nameValuePair.substring(0, index).trim();
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
      if (SECURE_ATTR.equalsIgnoreCase(name)) {
        secure = true;
      }
      if (HTTP_ONLY_ATTR.equalsIgnoreCase(name)) {
        httpOnly = true;
      }
      if (SAME_SITE_ATTR.equalsIgnoreCase(name)) {
        sameSite = value;
      }
    }
    return new Cookie(cookieName, secure, httpOnly, sameSite);
  }

  private static List<String> splitMultiCookies(final String header) {
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

  private static int guessCookieVersion(String header) {
    header = header.toLowerCase();
    if (header.contains("expires=")) {
      // only netscape cookie using 'expires'
      return 0;
    } else if (header.contains("version=")) {
      // version is mandatory for rfc 2965/2109 cookie
      return 1;
    } else if (header.contains("max-age")) {
      // rfc 2965/2109 use 'max-age'
      return 1;
    }
    return 0;
  }
}
