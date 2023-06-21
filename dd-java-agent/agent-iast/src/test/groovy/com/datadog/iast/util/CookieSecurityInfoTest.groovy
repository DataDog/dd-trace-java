package com.datadog.iast.util


import groovy.transform.CompileDynamic
import spock.lang.Specification

@CompileDynamic
class CookieSecurityInfoTest extends Specification {

  void 'parsing cookie header'() {
    when:
    final cookieSecurityInfo = new CookieSecurityParser(header)

    then:
    cookieName == cookieSecurityInfo.getCookieName()
    isSecure == cookieSecurityInfo.isSecure()
    isHttpOnly == cookieSecurityInfo.isHttpOnly()
    isSameSiteStrict == cookieSecurityInfo.isSameSiteStrict()

    where:
    header                                              | cookieName      | isSecure | isHttpOnly | isSameSiteStrict
    "user-id=7"                                         | "user-id"       | false    | false      | false
    "user-id=7;Secure"                                  | "user-id"       | true     | false      | false
    "user-id=7;Secure;HttpOnly=true"                    | "user-id"       | true     | true       | false
    "user-id=7;Secure;HttpOnly=true;SameSite=Strict"    | "user-id"       | true     | true       | true
  }
}
