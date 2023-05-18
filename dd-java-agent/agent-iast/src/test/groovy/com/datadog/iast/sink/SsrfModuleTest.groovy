package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class SsrfModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private SsrfModule module

  private AgentSpan span

  def setup() {
    module = registerDependencies(new SsrfModuleImpl())
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

  void 'test SSRF detection'() {
    when:
    module.onURLConnection(url)

    then: 'report is not called if no active span'
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    when:
    module.onURLConnection(url)

    then: 'report is not called if url is not tainted'
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    when:
    taint(url)
    module.onURLConnection(url)

    then: 'report is called when the url is tainted'
    tracer.activeSpan() >> span
    1 * reporter.report(span, { Vulnerability vul -> vul.type == VulnerabilityType.SSRF })

    where:
    url                        | _
    new URL('http://test.com') | _
    'http://test.com'          | _
  }

  private void taint(final Object value) {
    ctx.getTaintedObjects().taintInputObject(value, new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', value.toString()))
  }
}
