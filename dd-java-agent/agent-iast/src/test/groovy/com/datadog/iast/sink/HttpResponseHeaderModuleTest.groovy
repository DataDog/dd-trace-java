package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.util.Cookie
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class HttpResponseHeaderModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private HttpResponseHeaderModuleImpl module

  private AgentSpan span

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new HttpResponseHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
    InstrumentationBridge.registerIastModule(new InsecureCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoHttpOnlyCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoSameSiteCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new HstsMissingHeaderModuleImpl(dependencies))
    InstrumentationBridge.registerIastModule(new UnvalidatedRedirectModuleImpl(dependencies))
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


  void 'check quota is consumed correctly'() {
    given:
    Map<VulnerabilityType, Vulnerability> savedVul = [:]
    final onReport = { Vulnerability vul ->
      savedVul.put(vul.type, vul)
    }
    final cookie = Cookie.named('user-id').build()

    when:
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId()
    1 * span.getServiceName()
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { onReport.call(it[1] as Vulnerability) }
    1 * reporter.report(_, _ as Vulnerability) >> { onReport.call(it[1] as Vulnerability) }
    1 * reporter.report(_, _ as Vulnerability) >> { onReport.call(it[1] as Vulnerability) }
    0 * _
    with(savedVul[VulnerabilityType.INSECURE_COOKIE]) {
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
    with(savedVul[VulnerabilityType.NO_HTTPONLY_COOKIE]) {
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
    with(savedVul[VulnerabilityType.NO_SAMESITE_COOKIE]) {
      type == VulnerabilityType.NO_SAMESITE_COOKIE
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
  }

  void 'exercise onHeader'() {
    when:
    module.onHeader("Set-Cookie", "user-id=7")
    module.onHeader("X-Content-Type-Options", "nosniff")
    module.onHeader("Content-Type", "text/html")
    module.onHeader("Strict-Transport-Security", "invalid max age")

    then:
    4 * tracer.activeSpan()
    1 * overheadController.consumeQuota(_,_)
    0 * _
  }

  void 'exercise IastRequestController'(){
    given:
    IastRequestContext ctx = new IastRequestContext()

    when:
    ctx.setxForwardedProto('https')

    then:
    ctx.getxForwardedProto() == 'https'
  }

  void 'exercise IastRequestContext'(){
    given:
    IastRequestContext ctx = new IastRequestContext()

    when:
    ctx.setxForwardedProto('https')
    ctx.setContentType("text/html")
    ctx.setxContentTypeOptions('nosniff')
    ctx.getxContentTypeOptions()
    ctx.setStrictTransportSecurity('max-age=2345')

    then:
    0 * _
  }
}
