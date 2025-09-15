package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.TagMap
import datadog.trace.api.gateway.Flow
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class HstsMissingHeaderModuleTest extends IastModuleImplTestBase {

  private HstsMissingHeaderModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new HstsMissingHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }

  @Override
  protected TraceSegment buildTraceSegment() {
    return Mock(TraceSegment)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  @Override
  protected OverheadController buildOverheadController() {
    return Mock(OverheadController) {
      acquireRequest() >> true
      consumeQuota(_ as Operation, _ as AgentSpan, _ as VulnerabilityType) >> true
    }
  }

  void 'hsts vulnerability'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    ctx.xForwardedProto = 'https'
    ctx.contentType = "text/html"
    span.getTags() >> TagMap.fromMap([
      'http.url': 'https://localhost/a',
      'http.status_code': 200i
    ])

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * overheadController.releaseRequest()
    1 * reporter.report(_, {
      final vul = it as Vulnerability
      assert vul.type == VulnerabilityType.HSTS_HEADER_MISSING
    })
  }

  void 'no hsts vulnerability reported'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    ctx.xForwardedProto = 'https'
    ctx.contentType = "text/html"
    span.getTags() >> [
      'http.url': url,
      'http.status_code': status
    ]

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * overheadController.releaseRequest()
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

  void 'exception not thrown if igSpanInfo is null'(){
    when:
    module.onRequestEnd(ctx, null)

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
