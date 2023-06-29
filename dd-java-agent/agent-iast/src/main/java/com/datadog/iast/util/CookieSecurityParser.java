package com.datadog.iast.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

public class CookieSecurityParser {
  ArrayList<CookieSecurityDetails> cookies = new ArrayList<>();

  public CookieSecurityParser(String cookieString) {
    try {
      int version = guessCookieVersion(cookieString);
      List<String> cookieStrings = null;
      if (0 != version) {
        cookieStrings = splitMultiCookies(cookieString);
      } else {
        cookieStrings = Arrays.asList(cookieString);
      }
      for (String cookieStr : cookieStrings) {
        CookieSecurityDetails cookie = parseInternal(cookieStr);
        cookies.add(cookie);
      }
    } catch (Exception e) {
      // Cookie is not parseable
    }
  }

  public ArrayList<CookieSecurityDetails> getCookies() {
    return cookies;
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
        analyzedCookie.setCookieName(nameValuePair.substring(0, index).trim());
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

  private static int guessCookieVersion(String header) {
    int version = 0;

    header = header.toLowerCase();
    if (header.indexOf("expires=") != -1) {
      // only netscape cookie using 'expires'
      version = 0;
    } else if (header.indexOf("version=") != -1) {
      // version is mandatory for rfc 2965/2109 cookie
      version = 1;
    } else if (header.indexOf("max-age") != -1) {
      // rfc 2965/2109 use 'max-age'
      version = 1;
    }

    return version;
  }
}
