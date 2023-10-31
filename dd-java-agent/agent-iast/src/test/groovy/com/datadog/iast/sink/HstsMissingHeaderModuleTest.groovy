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

class HstsMissingHeaderModuleTest extends IastModuleImplTestBase {

  private TraceSegment traceSegment

  private RequestContext reqCtx

  private IastRequestContext iastCtx

  private HstsMissingHeaderModuleImpl module

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
    module = new HstsMissingHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }


  void 'hsts vulnerability'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    iastCtx.getxForwardedProto() >> 'https'
    iastCtx.getContentType() >> "text/html"
    span.getTags() >> [
      'http.url': 'https://localhost/a',
      'http.status_code': 200i
    ]

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * overheadController.releaseRequest()
    1 * iastCtx.getStrictTransportSecurity()
    1 * reporter.report(_, {
      final vul = it as Vulnerability
      assert vul.type == VulnerabilityType.HSTS_HEADER_MISSING
    })
  }

  void 'no hsts vulnerability reported'() {
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
    1 * iastCtx.getStrictTransportSecurity()
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
    thrown(NullPointerException)
  }

  void 'exception not thrown if igSpanInfo is null'(){
    when:
    module.onRequestEnd(iastCtx, null)

    then:
    noExceptionThrown()
  }

  void 'test max age'(){
    when:
    final result = HstsMissingHeaderModuleImpl.isValidMaxAge(value)

    then:
    result == expected

    where:
    value               | expected
    "max-age=0"         | false
    "max-age=-1"        | false
    null                | false
    ""                  | false
    "max-age-3"         | false
    "ramdom"            | false
    "max-age=10"        | true
    "max-age=0122344"   | true
  }
}
