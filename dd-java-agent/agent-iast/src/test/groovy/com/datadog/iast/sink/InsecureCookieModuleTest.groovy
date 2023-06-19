package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import com.datadog.iast.util.CookieSecurityInfo
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

@CompileDynamic
class InsecureCookieModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private InsecureCookieModuleImpl module
  private NoHttpOnlyCookieModuleImpl moduleNoHttp


  private AgentSpan span

  def setup() {
    module = registerDependencies(new InsecureCookieModuleImpl())
    moduleNoHttp = registerDependencies(new NoHttpOnlyCookieModuleImpl())
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

  void 'report insecure cookie with InsecureCookieModule.onCookie'() {
    given:
    Vulnerability savedVul
    final cookie = new CookieSecurityInfo(cookieValue)


    when:
    module.onCookie(cookie)

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

  void 'check quota is consumed correctly'() {
    given:
    Vulnerability savedVul1
    Vulnerability savedVul2
    final cookie = new CookieSecurityInfo(cookieValue)

    when:
    moduleNoHttp.onCookie(cookie)
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId()
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul1 = it[1] }
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul2 = it[1] }
    0 * _
    with(savedVul1) {
      type == VulnerabilityType.NO_HTTPONLY_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }
    with(savedVul2) {
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

  void 'check quota in inverser order'() {
    given:
    Vulnerability savedVul1
    Vulnerability savedVul2
    final cookie = new CookieSecurityInfo(cookieValue)

    when:
    module.onCookie(cookie)
    moduleNoHttp.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId()
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul1 = it[1] }
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul2 = it[1] }
    0 * _
    with(savedVul1) {
      type == VulnerabilityType.INSECURE_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }
    with(savedVul2) {
      type == VulnerabilityType.NO_HTTPONLY_COOKIE
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
    final cookie = new CookieSecurityInfo(cookieName, isSecure, false, false)

    when:
    module.onCookie(cookie)

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
    cookieName | cookieValue | isSecure | expected
    "user-id"  | "7"         | false    | "user-id"
  }

  void 'cases where nothing is  not reported during InsecureCookieModuleTest.onCookie'() {
    given:
    final cookie = new CookieSecurityInfo(cookieValue)

    when:
    module.onCookie(cookie)

    then:
    0 * tracer.activeSpan()
    0 * overheadController._
    0 * reporter._

    where:
    cookieValue         | _
    "user-id=7; Secure" | _
    "user-id=7;Secure"  | _
  }

  void 'insecure cookie is not reported with InsecureCookieModule.onCookie'() {
    given:
    final cookie = new CookieSecurityInfo(cookieName, isSecure, false, false)

    when:
    module.onCookie(cookie)

    then:
    0 * tracer.activeSpan() >> span
    0 * overheadController.consumeQuota(_, _) >> true
    0 * reporter.report(_, _ as Vulnerability)

    where:
    cookieName | cookieValue | isSecure
    "user-id"  | "7"         | true
  }
}
