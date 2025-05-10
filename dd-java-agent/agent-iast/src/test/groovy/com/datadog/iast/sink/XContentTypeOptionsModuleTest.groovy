package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.TagMap
import datadog.trace.api.gateway.Flow
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.internal.TraceSegment

class XContentTypeOptionsModuleTest extends IastModuleImplTestBase {

  private XContentTypeModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new XContentTypeModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  @Override
  protected TraceSegment buildTraceSegment() {
    return Mock(TraceSegment)
  }

  void 'x content options sniffing vulnerability'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    ctx.contentType = "text/html"
    span.getTags() >> TagMap.fromMap([
      'http.status_code': 200i
    ])

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * reporter.report(_, {
      final vul = it as Vulnerability
      assert vul.type == VulnerabilityType.XCONTENTTYPE_HEADER_MISSING
    })
  }


  void 'no x content options sniffing reported'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    ctx.xForwardedProto = 'https'
    ctx.contentType = "text/html"
    span.getTags() >> TagMap.fromMap([
      'http.url': url,
      'http.status_code': status
    ])

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
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



  void 'ignore if context is null'(){
    when:
    module.onRequestEnd(null, null)

    then:
    0 * _
  }
}
