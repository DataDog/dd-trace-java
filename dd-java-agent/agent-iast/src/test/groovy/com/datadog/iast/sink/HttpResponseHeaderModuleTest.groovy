package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class HttpResponseHeaderModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private HttpResponseHeaderModuleImpl module

  private AgentSpan span

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = registerDependencies(new HttpResponseHeaderModuleImpl())
    InstrumentationBridge.registerIastModule(new InsecureCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoHttpOnlyCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoSameSiteCookieModuleImpl())
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
    Vulnerability savedVul1
    Vulnerability savedVul2
    Vulnerability savedVul3

    when:
    module.onCookie("user-id", false, false, false)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId()
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul1 = it[1] }
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul2 = it[1] }
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul3 = it[1] }
    0 * _
    with(savedVul1) {
      type == VulnerabilityType.INSECURE_COOKIE
      location != null
      with(evidence) {
        value == "user-id"
      }
    }
    with(savedVul2) {
      type == VulnerabilityType.NO_HTTPONLY_COOKIE
      location != null
      with(evidence) {
        value == "user-id"
      }
    }
    with(savedVul3) {
      type == VulnerabilityType.NO_SAMESITE_COOKIE
      location != null
      with(evidence) {
        value == "user-id"
      }
    }
  }

  void 'exercise onHeader'() {
    when:
    module.onHeader("Set-Cookie", "user-id=7")

    then:
    1 * tracer.activeSpan()
    1 * overheadController.consumeQuota(_,_)
    0 * _
  }
}
