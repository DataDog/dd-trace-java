package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class XContentTypeOptionsModuleTest extends IastModuleImplTestBase {

  private TraceSegment traceSegment

  private RequestContext reqCtx

  private IastRequestContext iastCtx

  private XContentTypeModuleImpl module

  private AgentSpan span

  def setup() {
    traceSegment = Mock(TraceSegment)
    iastCtx = Spy(new IastRequestContext())
    reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
      getTraceSegment() >> traceSegment
    }
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
    InstrumentationBridge.clearIastModules()
    module = new XContentTypeModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }


  void 'x content options sniffing vulnerability'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    iastCtx.getContentType() >> "text/html"
    span.getTags() >> [
      'http.status_code': 200i
    ]

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * overheadController.releaseRequest()
    1 * iastCtx.getxContentTypeOptions() >> null
    1 * reporter.report(_, {
      final vul = it as Vulnerability
      assert vul.type == VulnerabilityType.XCONTENTTYPE_HEADER_MISSING
    })
  }


  void 'no x content options sniffing reported'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    iastCtx.getxForwardedProto() >> 'https'
    iastCtx.getContentType() >> "text/html"
    span.getTags() >> [
      'http.url': url,
      'http.status_code': status
    ]

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * overheadController.releaseRequest()
    1 * iastCtx.getxContentTypeOptions()
    0 * reporter.report(_, _)

    where:
    url                   | status
    "https://localhost/a" | 307i
    "https://localhost/a" | HttpURLConnection.HTTP_MOVED_PERM
    "https://localhost/a" | HttpURLConnection.HTTP_MOVED_TEMP
    "https://localhost/a" | HttpURLConnection.HTTP_NOT_MODIFIED
    "https://localhost/a" | HttpURLConnection.HTTP_NOT_FOUND
    "https://localhost/a" | HttpURLConnection.HTTP_GONE
    "https://localhost/a" | HttpURLConnection.HTTP_INTERNAL_ERROR
  }



  void 'throw exception if context is null'(){
    when:
    module.onRequestEnd(null, null)

    then:
    noExceptionThrown()
  }
}
