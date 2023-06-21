package com.datadog.iast.util;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

public class CookieSecurityParser {
  ArrayList<CookieSecurityDetails> badCookies = new ArrayList<>();

  public CookieSecurityParser(String cookieString) {

    List<CookieSecurityDetails> cookies = new java.util.ArrayList<>();

    List<String> cookieStrings = splitMultiCookies(cookieString);
    for (String cookieStr : cookieStrings) {
      CookieSecurityDetails cookie = parseInternal(cookieStr);
      if (!cookie.isHttpOnly || !cookie.isSecure || !cookie.isSameSiteStrict) {
        badCookies.add(cookie);
      }
    }
  }

  public ArrayList<CookieSecurityDetails> getBadCookies() {
    return badCookies;
  }

  private CookieSecurityDetails parseInternal(String header) {
    CookieSecurityDetails analyzedCookie = new CookieSecurityDetails();

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
}
