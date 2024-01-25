package com.datadog.iast.util


import com.datadog.iast.sink.InsecureCookieModuleImpl
import com.datadog.iast.sink.NoHttpOnlyCookieModuleImpl
import com.datadog.iast.sink.NoSameSiteCookieModuleImpl
import datadog.trace.api.iast.InstrumentationBridge
import groovy.transform.CompileDynamic
import spock.lang.Specification

@CompileDynamic
class CookieSecurityParserTest extends Specification {
  def setup() {
    InstrumentationBridge.clearIastModules()
    InstrumentationBridge.registerIastModule(new InsecureCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoHttpOnlyCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoSameSiteCookieModuleImpl())
  }

  void 'parsing single cookie header'() {
    when:
    final badCookies = CookieSecurityParser.parse(header)
    final badCookie = badCookies.first()

    then:
    cookieName == badCookie.getCookieName()
    isSecure == badCookie.isSecure()
    isHttpOnly == badCookie.isHttpOnly()
    sameSite == badCookie.getSameSite()

    where:
    header                                                                                                                                                       | cookieName | isSecure | isHttpOnly | sameSite
    "Set-Cookie: user-id=7"                                                                                                                                      | "user-id"  | false    | false      | null
    'Set-Cookie: user-id="7"'                                                                                                                                    | "user-id"  | false    | false      | null
    "Set-Cookie: user-id=7;Secure"                                                                                                                               | "user-id"  | true     | false      | null
    "Set-Cookie: user-id=7;Secure;HttpOnly"                                                                                                                      | "user-id"  | true     | true       | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; version='1'"                                                                                                            | "CUSTOMER" | false    | false      | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wednesday, 09-Nov-99 23:12:40 GMT"                                                                      | "CUSTOMER" | false    | false      | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wednesday, 09-Nov-99 23:12:40 GMT;SameSite=Lax;HttpOnly"                                                | "CUSTOMER" | false    | true       | 'Lax'
    "Set-Cookie: PREF=ID=1eda537de48ac25d:CR=1:TM=1112868587:LM=1112868587:S=t3FPA-mT9lTR3bxU;expires=Sun, 17-Jan-2038 19:14:07 GMT; path=/; domain=.google.com" | "PREF"     | false    | false      | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wednesday, 09-Nov-99 23:12:40 GMT; Secure"                                                              | "CUSTOMER" | true     | false      | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wednesday, 09-Nov-99 23:12:40 GMT; path=\"/acme\";SameSite=Strict"                                      | "CUSTOMER" | false    | false      | 'Strict'
  }


  void 'parsing multi cookie header'() {
    when:
    final badCookies = CookieSecurityParser.parse(headerValue)

    then:
    badCookies.size() == 3

    badCookies.get(0).getCookieName() == 'A'
    badCookies.get(0).isSecure()
    badCookies.get(0).isHttpOnly()
    badCookies.get(0).getSameSite() == 'Strict'


    badCookies.get(1).getCookieName() == 'B'
    badCookies.get(1).isSecure()
    !badCookies.get(1).isHttpOnly()
    badCookies.get(1).getSameSite() == 'Strict'

    badCookies.get(2).getCookieName() == 'C'
    !badCookies.get(2).isSecure()
    !badCookies.get(2).isHttpOnly()
    badCookies.get(2).getSameSite() == null

    where:
    headerValue                                                                                       | _
    "Set-Cookie: A=1;Secure;HttpOnly=true;SameSite=Strict;version='1',B=2;Secure;SameSite=Strict,C=3" | _
    "Set-Cookie2: A=1;Secure;HttpOnly=true;SameSite=Strict,B=2;Secure;SameSite=Strict,C=3"            | _
  }

  void 'parsing wrong header'() {
    when:
    final cookies = CookieSecurityParser.parse('Un-Set-Cookie: user-id=7')

    then:
    cookies.empty
  }

  void 'parsing badly formatted cookies'() {
    when:
    final cookies = CookieSecurityParser.parse(header)

    then:
    cookies.size() == 1
    final cookie = cookies.first()
    cookie.cookieName == 'user-id'
    cookie.secure


    where:
    header                              | _
    'Set-Cookie: user-id=7;;;Secure;;'  | _
    'Set-Cookie: user-id=7;Secure===;;' | _
  }
}
