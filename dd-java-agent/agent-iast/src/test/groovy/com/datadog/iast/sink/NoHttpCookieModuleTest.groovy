package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.util.Cookie
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import groovy.transform.CompileDynamic

@CompileDynamic
class NoHttpCookieModuleTest extends IastModuleImplTestBase {

  private HttpResponseHeaderModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new HttpResponseHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(new NoHttpOnlyCookieModuleImpl())
  }

  @Override
  protected AgentTracer.TracerAPI buildAgentTracer() {
    return Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
      getTraceSegment() >> traceSegment
    }
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'report NoHttp cookie with NoHttpOnlyCookieModule.onCookie'() {
    given:
    Vulnerability savedVul
    final cookie = Cookie.named('user-id').build()

    when:
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.NO_HTTPONLY_COOKIE
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
  }

  void 'cases where nothing is reported during NoHttpModuleCookie.onCookie'() {
    given:
    final cookie = Cookie.named('user-id')
      .secure(true)
      .httpOnly(true)
      .sameSite('Strict')
      .build()

    when:
    module.onCookie(cookie)

    then:
    0 * tracer.activeSpan()
    0 * reporter._
  }

  void 'HttpOnly cookie is not reported with NoHttpOnlyCookieModule.onCookie'() {
    given:
    final cookie = Cookie.named('user-id').httpOnly(true).build()

    when:
    module.onCookie(cookie)

    then:
    0 * tracer.activeSpan() >> span
    0 * reporter.report(_, _ as Vulnerability)
  }
}
