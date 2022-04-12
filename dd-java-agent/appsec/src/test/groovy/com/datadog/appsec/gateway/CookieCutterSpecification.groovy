package com.datadog.appsec.gateway

import datadog.trace.test.util.DDSpecification

class CookieCutterSpecification extends DDSpecification {

  void 'basic test'() {
    when:
    def res = CookieCutter.parseCookieHeader("a=b; c=d")

    then:
    res['a'] == ['b']
    res['c'] == ['d']
  }

  void 'empty inputs'() {
    expect:
    CookieCutter.parseCookieHeader(null) == [:]
    CookieCutter.parseCookieHeader(' ') == [:]
  }

  void 'quoted values'() {
    expect:
    CookieCutter.parseCookieHeader("a=\"$encoded\"") == [a: [decoded]]

    where:
    encoded | decoded
    'b'     | 'b'
    'b\\"b' | 'b"b'
    'b"b'   | 'b"b'
    'b\\b'  | 'bb'
  }

  void 'quoted names'() {
    expect:
    CookieCutter.parseCookieHeader("\"$encoded\"=b") == [(decoded): ['b']]

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
    CookieCutter.parseCookieHeader('a="b') == [:]
  }

  void 'empty name'() {
    expect:
    CookieCutter.parseCookieHeader('=b') == [:]
  }

  void 'empty pair'() {
    expect:
    CookieCutter.parseCookieHeader('; a=b') == [a: ['b']]
  }

  void 'empty value'() {
    expect:
    // strange behavior, but it's what jetty does; let's keep it
    CookieCutter.parseCookieHeader('a=') == [:]
    CookieCutter.parseCookieHeader('a;') == [a: ['']]
    CookieCutter.parseCookieHeader('a') == [a: ['']]
    CookieCutter.parseCookieHeader('a"') == ['a"': ['']]
    CookieCutter.parseCookieHeader('"a"') == [a: ['']]
    CookieCutter.parseCookieHeader('a=;') == [a: ['']]
  }

  void 'value has trailing space'() {
    expect:
    CookieCutter.parseCookieHeader('a=bcd\t ') == [a: ['bcd']]
  }

  void 'value has space in the middle'() {
    expect:
    CookieCutter.parseCookieHeader('a=b c\td') == [a: ['b c\td']]
  }

  void 'names starting with dollar sign are ignored'() {
    expect:
    CookieCutter.parseCookieHeader('a=b; $c=d') == [a: ['b']]
  }
}
