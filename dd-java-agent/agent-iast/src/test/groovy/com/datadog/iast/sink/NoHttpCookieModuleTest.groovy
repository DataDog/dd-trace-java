package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

@CompileDynamic
class NoHttpCookieModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private NoHttpOnlyCookieModuleImpl module

  private AgentSpan span

  def setup() {
    module = registerDependencies(new NoHttpOnlyCookieModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
  }

  void 'report NoHttp cookie with InsecureCookieModule.onCookies'() {
    given:
    Vulnerability savedVul

    when:
    module.onCookies(HttpCookie.parse(cookieValue))

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.NO_HTTP_ONLY_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }

    where:
    cookieValue | expected
    "user-id=7" | "user-id"
  }
  void 'report insecure cookie with NoHttpOnlyCookieModule.onCookie'() {
    given:
    Vulnerability savedVul

    when:
    module.onCookie(cookieName, isHttpOnly)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.NO_HTTP_ONLY_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }

    where:
    cookieName | isHttpOnly | expected
    "user-id" | false | "user-id"
  }

  void 'cases where nothing is reported during NoHttpModuleCookie.onCookies'() {

    when:
    module.onCookies(HttpCookie.parse(cookieValue))

    then:
    0 * tracer.activeSpan()
    0 * overheadController._
    0 * reporter._

    where:
    cookieValue         | _
    "user-id=7; HttpOnly" | _
    "user-id=7;HttpOnly"  | _
  }

  void 'insecure no http only is not reported with NoHttpOnlyCookieModule.onCookie'() {

    when:
    module.onCookie(cookieName, isHttpOnly)

    then:
    0 * tracer.activeSpan() >> span
    0 * overheadController.consumeQuota(_, _) >> true
    0 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }

    where:
    cookieName | isHttpOnly
    "user-id" | true
  }
}
