package com.datadog.appsec.gateway;

import static com.datadog.appsec.gateway.CookieCutter.parseCookieHeader;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CookieCutterTest {

  @Test
  void basicTest() {
    // when
    Map<String, List<String>> result = parseCookieHeader("a=b; c=d");

    // then
    assertEquals(singletonList("b"), result.get("a"));
    assertEquals(singletonList("d"), result.get("c"));
    assertEquals(2, result.size());
  }

  @Test
  void emptyInputs() {
    assertEquals(emptyMap(), parseCookieHeader(null));
    assertEquals(emptyMap(), parseCookieHeader(" "));
  }

  @TableTest({
    "scenario        | encoded  | decoded",
    "plain           | b        | b      ",
    "escaped quote   | 'b\\\"b' | 'b\"b' ",
    "unescaped quote | 'b\"b'   | 'b\"b' ",
    "escaped char    | 'b\\b'   | bb     "
  })
  void quotedValues(String encoded, String decoded) {
    assertEquals(cookie("a", decoded), parseCookieHeader("a=\"" + encoded + "\""));
  }

  @TableTest({
    "scenario        | encoded  | decoded",
    "plain           | b        | b      ",
    "escaped quote   | 'b\\\"b' | 'b\"b' ",
    "unescaped quote | 'b\"b'   | 'b\"b' ",
    "escaped char    | 'b\\b'   | bb     ",
    "equal sign      | 'b='     | 'b='   ",
    "semicolon       | 'b;'     | 'b;'   "
  })
  void quotedNames(String encoded, String decoded) {
    assertEquals(cookie(decoded, "b"), parseCookieHeader("\"" + encoded + "\"=b"));
  }

  @Test
  void missingTrailingQuote() {
    assertEquals(emptyMap(), parseCookieHeader("a=\"b"));
  }

  @Test
  void emptyName() {
    assertEquals(emptyMap(), parseCookieHeader("=b"));
  }

  @Test
  void emptyPair() {
    assertEquals(cookie("a", "b"), parseCookieHeader("; a=b"));
  }

  @Test
  void emptyValue() {
    // strange behavior, but it's what jetty does; let's keep it
    assertEquals(emptyMap(), parseCookieHeader("a="));
    assertEquals(cookie("a", ""), parseCookieHeader("a;"));
    assertEquals(cookie("a", ""), parseCookieHeader("a"));
    assertEquals(cookie("a\"", ""), parseCookieHeader("a\""));
    assertEquals(cookie("a", ""), parseCookieHeader("\"a\""));
    assertEquals(cookie("a", ""), parseCookieHeader("a=;"));
  }

  @Test
  void valueHasTrailingSpace() {
    assertEquals(cookie("a", "bcd"), parseCookieHeader("a=bcd\t "));
  }

  @Test
  void valueHasSpaceInTheMiddle() {
    assertEquals(cookie("a", "b c\td"), parseCookieHeader("a=b c\td"));
  }

  @Test
  void namesStartingWithDollarSignAreIgnored() {
    assertEquals(cookie("a", "b"), parseCookieHeader("a=b; $c=d"));
  }

  private static Map<String, List<String>> cookie(String name, String value) {
    return singletonMap(name, singletonList(value));
  }
}
