package com.datadog.iast.util;

import static com.datadog.iast.util.HttpHeader.SET_COOKIE2;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.util.Collections.emptyList;

import datadog.trace.api.iast.util.Cookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CookieSecurityParser {

  private static final Logger LOG = LoggerFactory.getLogger(CookieSecurityParser.class);

  private static final String SECURE = "Secure";
  private static final String HTTP_ONLY = "HttpOnly";
  private static final String SAME_SITE = "SameSite";
  private static final String EXPIRES = "Expires";
  private static final String VERSION = "Version";
  private static final String MAX_AGE = "Max-Age";

  // states for the FSM
  private static final byte COOKIE_NAME = 1;
  private static final byte COOKIE_VALUE = 2;
  private static final byte COOKIE_ATTR_NAME = 3;
  private static final byte COOKIE_ATTR_VALUE = 4;
  private static final byte SAME_SITE_ATTR = 1;
  private static final byte EXPIRES_ATTR = 2;
  private static final byte MAX_AGE_ATTR = 3;

  public static List<Cookie> parse(final String header) {
    final int end = header.indexOf(':');
    if (end < 0) {
      return Collections.emptyList();
    }
    final String headerName = header.substring(0, end).trim();
    final HttpHeader httpHeader = HttpHeader.from(headerName);
    if (httpHeader != HttpHeader.SET_COOKIE && httpHeader != SET_COOKIE2) {
      return Collections.emptyList();
    }
    final String headerValue = header.substring(end + 1).trim();
    return parse(httpHeader, headerValue);
  }

  /** Cookie parsing algo based on a little FSM */
  public static List<Cookie> parse(final HttpHeader headerName, String headerValue) {
    if (headerValue == null || headerValue.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      // only rfc 2965 cookie starts with 'set-cookie2'
      int version = headerName == SET_COOKIE2 ? 1 : 0;
      List<Cookie> result = new ArrayList<>();
      int start = 0, quoteCount = 0;
      Integer maxAge = null;
      Integer expiresYear = null;
      String cookieName = null, cookieValue = null, sameSite = null;
      boolean secure = false, httpOnly = false;
      byte state = COOKIE_NAME, attribute = -1;
      for (int i = 0; i < headerValue.length(); i++) {
        final char next = headerValue.charAt(i);
        if (next == '"') {
          quoteCount++;
        }
        boolean addCookie = false;
        boolean eof = i == headerValue.length() - 1;
        boolean separator = next == ',' || next == '=' || next == ';';
        if (eof || separator) {
          if (next == ',') {
            if (quoteCount % 2 == 0 && version == 1) {
              addCookie = true; // multiple cookie separator
            } else {
              continue;
            }
          }
          final int end = separator ? i : i + 1;
          switch (state) {
            case COOKIE_NAME:
              cookieName = headerValue.substring(start, end).trim();
              state = next == '=' ? COOKIE_VALUE : COOKIE_ATTR_NAME;
              break;
            case COOKIE_VALUE:
              if (headerValue.charAt(start) == '"' && headerValue.charAt(end - 1) == '"') {
                if (i != start + 1) { // avoid empty value ""
                  cookieValue = headerValue.substring(start + 1, end - 1).trim();
                }
              } else {
                cookieValue = headerValue.substring(start, end).trim();
              }
              state = COOKIE_ATTR_NAME;
              break;
            case COOKIE_ATTR_NAME:
              attribute = -1;
              final int from = trimLeft(start, headerValue);
              final int length = trimRight(end, headerValue) - from;
              if (equalsIgnoreCase(SECURE, headerValue, from, length)) {
                secure = true;
              } else if (equalsIgnoreCase(HTTP_ONLY, headerValue, from, length)) {
                httpOnly = true;
              } else if (equalsIgnoreCase(EXPIRES, headerValue, from, length)) {
                version = 0; // only netscape cookie using 'expires'
                attribute = EXPIRES_ATTR;
              } else if (equalsIgnoreCase(VERSION, headerValue, from, length)) {
                version = 1; // version is mandatory for rfc 2965/2109 cookie
              } else if (equalsIgnoreCase(MAX_AGE, headerValue, from, length)) {
                version = 1; // rfc 2965/2109 use 'max-age'
                attribute = MAX_AGE_ATTR;
              } else if (equalsIgnoreCase(SAME_SITE, headerValue, from, length)) {
                attribute = SAME_SITE_ATTR;
              }
              state = next == '=' ? COOKIE_ATTR_VALUE : COOKIE_ATTR_NAME;
              break;
            default: // COOKIE_ATTR_VALUE
              if (attribute > 0) {
                final String value = headerValue.substring(start, end).trim();
                switch (attribute) {
                  case SAME_SITE_ATTR:
                    sameSite = value;
                    break;
                  case EXPIRES_ATTR:
                    expiresYear = parseExpires(value, headerValue);
                    break;
                  case MAX_AGE_ATTR:
                    maxAge = parseMaxAge(value, headerValue);
                    break;
                  default:
                    break;
                }
              }
              state = COOKIE_ATTR_NAME;
              break;
          }
          start = end + 1;
        }
        if (addCookie || eof) {
          if (cookieName != null && !cookieName.isEmpty()) {
            result.add(
                new Cookie(
                    cookieName, cookieValue, secure, httpOnly, sameSite, expiresYear, maxAge));
          }
          cookieName = null;
          cookieValue = null;
          attribute = -1;
          secure = false;
          httpOnly = false;
          sameSite = null;
          expiresYear = null;
          maxAge = null;
          state = COOKIE_NAME;
        }
      }
      return result;
    } catch (final Throwable e) {
      LOG.debug(SEND_TELEMETRY, "Failed to parse the cookie {}", headerValue, e);
      return emptyList();
    }
  }

  @Nullable
  private static Integer parseMaxAge(final String value, final String headerValue) {
    try {
      return Integer.parseInt(value);
    } catch (final NumberFormatException e) {
      LOG.debug(SEND_TELEMETRY, "Failed to parse the max-age {}", headerValue);
      return null;
    }
  }

  @Nullable
  private static Integer parseExpires(final String value, final String headerValue) {

    Integer year = null;
    try {
      int count = 0;
      int start = 0;
      for (int i = 0; i < value.length(); i++) {
        final char next = value.charAt(i);
        if (next == ' ') {
          count++;
          if (count == 4) {
            year = Integer.parseInt(value.substring(start, i));
            break;
          } else {
            start = i + 1;
          }
        }
      }
    } catch (Exception e) {
      year = null;
    }
    if (year == null) {
      LOG.debug(SEND_TELEMETRY, "Failed to parse the expires {}", headerValue);
    }
    return year;
  }

  private static int trimLeft(int start, final String value) {
    while (Character.isWhitespace(value.charAt(start))) {
      start++;
    }
    return start;
  }

  private static int trimRight(int end, final String value) {
    while (Character.isWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return end;
  }

  private static boolean equalsIgnoreCase(
      final String token, final String value, int start, final int length) {
    return token.regionMatches(true, 0, value, start, length);
  }
}
