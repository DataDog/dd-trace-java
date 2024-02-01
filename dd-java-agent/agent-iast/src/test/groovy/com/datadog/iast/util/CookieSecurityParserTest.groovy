package com.datadog.iast.util


import com.datadog.iast.sink.InsecureCookieModuleImpl
import com.datadog.iast.sink.NoHttpOnlyCookieModuleImpl
import com.datadog.iast.sink.NoSameSiteCookieModuleImpl
import datadog.trace.api.iast.InstrumentationBridge
import groovy.transform.CompileDynamic
import spock.lang.Specification

import java.text.SimpleDateFormat


@CompileDynamic
class CookieSecurityParserTest extends Specification {

  static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z")

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
    cookieValue == badCookie.getCookieValue()
    isSecure == badCookie.isSecure()
    isHttpOnly == badCookie.isHttpOnly()
    sameSite == badCookie.getSameSite()
    maxAge == badCookie.getMaxAge()
    expiresYear == badCookie.getExpiresYear()

    where:
    header                                                                                                                                                       | cookieName | cookieValue                                                               | isSecure | isHttpOnly | sameSite | expiresYear | maxAge
    "Set-Cookie: user-id="                                                                                                                                      | "user-id"  | null                                                                       | false    | false      | null | null    | null
    "Set-Cookie: user-id"                                                                                                                                      | "user-id"  | null                                                                       | false    | false      | null | null    | null
    'Set-Cookie: user-id=""'                                                                                                                                      | "user-id"  | '""'                                                                       | false    | false      | null | null    | null
    "Set-Cookie: user-id=7"                                                                                                                                      | "user-id"  | '7'                                                                       | false    | false      | null | null    | null
    'Set-Cookie: user-id="7"'                                                                                                                                    | "user-id"  | '"7"'                                                                       | false    | false      | null | null    | null
    "Set-Cookie: user-id=7;Secure"                                                                                                                               | "user-id"  | '7'                                                                       | true     | false      | null | null    | null
    "Set-Cookie: user-id=7;Secure;HttpOnly"                                                                                                                      | "user-id"  | '7'                                                                       | true     | true       | null | null    | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; version='1'"                                                                                                            | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | null | null    | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wed, 21 Oct 2015 07:28:00 GMT;SameSite=Lax;HttpOnly"                                                | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | true       | 'Lax' | 2015    | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wed, 21 Oct 2015 07:28:00 GMT; Secure"                                                              | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | true     | false      | null | 2015    | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wed, 21 Oct 2015 07:28:00 GMT"                                                                      | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | null | 2015   | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=Wed, 21 Oct 2015 07:28:00 GMT; path=\"/acme\";SameSite=Strict"                                      | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | 'Strict' | 2015    | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; expires=BAD; path=\"/acme\";SameSite=Strict"                                      | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | 'Strict' | null    | null
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; Max-Age=3;SameSite=Lax;HttpOnly"                                                | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | true       | 'Lax' | null    | 3
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; Max-Age=3; Secure"                                                              | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | true     | false      | null | null    | 3
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; Max-Age=3"                                                                      | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | null | null    | 3
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; Max-Age=3; path=\"/acme\";SameSite=Strict"                                      | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | 'Strict' | null    | 3
    "Set-Cookie: CUSTOMER=WILE_E_COYOTE; path=/; Max-Age=BAD; path=\"/acme\";SameSite=Strict"                                      | "CUSTOMER" | 'WILE_E_COYOTE'                                                           | false    | false      | 'Strict' | null    | null
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
