package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

@CompileDynamic
class InsecureCookieModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private InsecureCookieModuleImpl module

  private AgentSpan span

  def setup() {
    module = registerDependencies(new InsecureCookieModuleImpl())
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

  void 'report insecure cookie with InsecureCookieModule.onCookieHeader'() {
    given:
    Vulnerability savedVul

    when:
    module.onCookieHeader(cookieValue)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.INSECURE_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }

    where:
    cookieValue | expected
    "user-id=7" | "user-id"
  }

  void 'report insecure cookie with InsecureCookieModule.onCookie'() {
    given:
    Vulnerability savedVul

    when:
    module.onCookie(cookieName, false)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.INSECURE_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }

    where:
    cookieName | expected
    "user-id"  | "user-id"
  }

  void 'cases where nothing is reported during InsecureCookieModule.onCookie'() {

    when:
    module.onCookie(cookieValue, isSecure)

    then:
    0 * tracer.activeSpan()
    0 * overheadController._
    0 * reporter._

    where:
    cookieValue | isSecure
    "user-id"   | true
    null        | true
    null        | false
    ""          | false
    ""          | true
  }


  void 'cases where nothing is reported during InsecureCookieModule.onCookieHeader'() {

    when:
    module.onCookieHeader(cookieValue)

    then:
    0 * tracer.activeSpan()
    0 * overheadController._
    0 * reporter._

    where:
    cookieValue         | _
    null                | _
    "user-id=7; Secure" | _
    "user-id7; Secure"  | _
    "user-id=7;Secure"  | _
    "blah"              | _
    ""                  | _
  }

  void 'if onHeader receives a cookie header call onCookieHeader'(final String headerName, final int expected) {
    setup:
    final icm = Spy(InsecureCookieModuleImpl)
    InstrumentationBridge.registerIastModule(icm)

    when:
    icm.onHeader(headerName, "value")

    then:
    expected * icm.onCookieHeader("value")


    where:
    headerName   | expected
    "blah"       | 0
    "Set-Cookie" | 1
    "set-cookie" | 1
  }
}
