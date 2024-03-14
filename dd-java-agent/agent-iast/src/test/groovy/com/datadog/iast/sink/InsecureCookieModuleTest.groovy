package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.util.Cookie
import groovy.transform.CompileDynamic

@CompileDynamic
class InsecureCookieModuleTest extends IastModuleImplTestBase {

  private HttpResponseHeaderModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new HttpResponseHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(new InsecureCookieModuleImpl())
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'report insecure cookie with InsecureCookieModule.onCookie'() {
    given:
    Vulnerability savedVul
    final cookie =  Cookie.named('user-id').value("123").build()

    when:
    module.onCookie(cookie)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.INSECURE_COOKIE
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
  }

  void 'cases where nothing is reported during InsecureCookieModuleTest.onCookie'() {
    given:
    final cookie = Cookie.named('user-id')
      .secure(secure)
      .value(value)
      .maxAge(maxAge)
      .expiresYear(expiresYear)
      .build()

    when:
    module.onCookie(cookie)

    then:
    0 * reporter.report(_, _ as Vulnerability)

    where:
    secure | value | maxAge | expiresYear
    true | "test" | null | null // secure cookies are not vulnerable
    false | null | null | null // cookies without a value are not vulnerable
    false | "" | null | null  // cookies without a value are not vulnerable
    false | "test" | 0 | null // cookies with a maxAge of 0 are not vulnerable
    false | "test" | null | 1970 // cookies with an expires year older than 2000 are not vulnerable
  }
}
