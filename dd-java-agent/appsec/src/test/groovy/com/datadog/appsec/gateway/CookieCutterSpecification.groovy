package com.datadog.appsec.gateway

import com.datadog.appsec.event.data.StringKVPair
import spock.lang.Specification

class CookieCutterSpecification extends Specification {

  void 'basic test'() {
    when:
    List<StringKVPair> res = CookieCutter.parseCookieHeader("a=b; c=d")

    then:
    res[0] == new StringKVPair('a', 'b')
    res[1] == new StringKVPair('c', 'd')
  }

  void 'empty inputs'() {
    expect:
    CookieCutter.parseCookieHeader(null) == []
    CookieCutter.parseCookieHeader(' ') == []
  }

  void 'quoted values'() {
    expect:
    CookieCutter.parseCookieHeader("a=\"$encoded\"") ==
      [new StringKVPair('a', decoded)]

    where:
    encoded | decoded
    'b'     | 'b'
    'b\\"b' | 'b"b'
    'b"b'   | 'b"b'
    'b\\b'  | 'bb'
  }

  void 'quoted names'() {
    expect:
    CookieCutter.parseCookieHeader("\"$encoded\"=b") ==
      [new StringKVPair(decoded, 'b')]

    where:
    encoded | decoded
    'b'     | 'b'
    'b\\"b' | 'b"b'
    'b"b'   | 'b"b'
    'b\\b'  | 'bb'
    'b='    | 'b='
    'b;'    | 'b;'
  }

  void 'missing trailing quote'() {
    expect:
    CookieCutter.parseCookieHeader('a="b') == []
  }

  void 'empty name'() {
    expect:
    CookieCutter.parseCookieHeader('=b') == []
  }

  void 'empty pair'() {
    expect:
    CookieCutter.parseCookieHeader('; a=b') == [new StringKVPair('a', 'b')]
  }

  void 'empty value'() {
    expect:
    // strange behavior, but it's what jetty does; let's keep it
    CookieCutter.parseCookieHeader('a=') == []
    CookieCutter.parseCookieHeader('a;') == [new StringKVPair('a', '')]
    CookieCutter.parseCookieHeader('a') == [new StringKVPair('a', '')]
    CookieCutter.parseCookieHeader('a"') == [new StringKVPair('a"', '')]
    CookieCutter.parseCookieHeader('"a"') == [new StringKVPair('a', '')]
    CookieCutter.parseCookieHeader('a=;') == [new StringKVPair('a', '')]
  }

  void 'value has trailing space'() {
    expect:
    CookieCutter.parseCookieHeader('a=bcd\t ') == [new StringKVPair('a', 'bcd')]
  }

  void 'value has space in the middle'() {
    expect:
    CookieCutter.parseCookieHeader('a=b c\td') == [new StringKVPair('a', 'b c\td')]
  }

  void 'names starting with dollar sign are ignored'() {
    expect:
    CookieCutter.parseCookieHeader('a=b; $c=d') == [['a', 'b']]
  }
}
