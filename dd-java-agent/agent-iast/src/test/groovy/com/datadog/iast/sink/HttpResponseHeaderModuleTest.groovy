package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.overhead.Operations
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.HeaderInjectionModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.util.Cookie
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

class HttpResponseHeaderModuleTest extends IastModuleImplTestBase {

  private HttpResponseHeaderModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new HttpResponseHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
    InstrumentationBridge.registerIastModule(new InsecureCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoHttpOnlyCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoSameSiteCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new HstsMissingHeaderModuleImpl(dependencies))
    InstrumentationBridge.registerIastModule(new UnvalidatedRedirectModuleImpl(dependencies))
    InstrumentationBridge.registerIastModule(new HeaderInjectionModuleImpl(dependencies))
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

  void 'check quota is consumed correctly'() {
    given:
    Map<VulnerabilityType, Vulnerability> savedVul = [:]
    final onReport = { Vulnerability vul ->
      savedVul.put(vul.type, vul)
    }
    final cookie = Cookie.named('user-id').value('123').build()

    when:
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
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
    module.onHeader(header, value)

    then:
    overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span, _ as VulnerabilityType) >> false // do not report in this test
    activeSpanCount * tracer.activeSpan() >>  {
      return span
    }
    0 * _

    where:
    header                      | value             | activeSpanCount
    "Set-Cookie"                | "user-id=7"       | 3
    "X-Content-Type-Options"    | "nosniff"         | 2
    "Content-Type"              | "text/html"       | 2
    "Strict-Transport-Security" | "invalid max age" | 2
    "Authorization"             | "Basic token"     | 2
  }

  void 'exercise IastRequestController'(){
    given:
    final taintedObjects = Stub(TaintedObjects)
    IastRequestContext ctx = new IastRequestContext(taintedObjects)

    when:
    ctx.setxForwardedProto('https')

    then:
    ctx.getxForwardedProto() == 'https'
  }

  void 'exercise IastRequestContext'(){
    given:
    final taintedObjects = Stub(TaintedObjects)

    when:
    IastRequestContext ctx = new IastRequestContext(taintedObjects)
    ctx.setxForwardedProto('https')
    ctx.setContentType("text/html")
    ctx.setxContentTypeOptions('nosniff')
    ctx.getxContentTypeOptions()
    ctx.setStrictTransportSecurity('max-age=2345')

    then:
    0 * _
  }

  void 'check HttpResponseHeaderModule calls HeaderInjectionModule on header'() {
    given:
    final headerInjectionModule = Mock(HeaderInjectionModule)
    InstrumentationBridge.registerIastModule(headerInjectionModule)
    final headerName = 'headerName'
    final headerValue = 'headerValue'

    when:
    module.onHeader(headerName, headerValue)

    then:
    1 * headerInjectionModule.onHeader(headerName, headerValue)
  }

  void 'check HttpResponseHeaderModule calls UnvalidatedRedirectModule on header'() {
    given:
    final unvalidatedRedirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(unvalidatedRedirectModule)
    final headerValue = 'headerValue'

    when:
    module.onHeader(headerName, headerValue)

    then:
    expected * unvalidatedRedirectModule.onHeader(headerName, headerValue)

    where:
    headerName               | expected
    'location'               | 1
    'Location'               | 1
    'headerName'             | 0
  }
}
