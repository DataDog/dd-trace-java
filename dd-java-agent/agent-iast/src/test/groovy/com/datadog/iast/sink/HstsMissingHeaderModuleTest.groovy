package com.datadog.iast.sink

import com.datadog.iast.HasDependencies
import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.Config
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.util.stacktrace.StackWalker

public class HstsMissingHeaderModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private HstsMissingHeaderModuleImpl module

  private AgentSpan span

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = registerDependencies(new HstsMissingHeaderModuleImpl())
    InstrumentationBridge.registerIastModule(module)
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


  void 'hsts vulnerability'() {
    given:
    Vulnerability savedVul1
    final OverheadController overheadController = Mock(OverheadController)
    final iastCtx = Mock(IastRequestContext)
    iastCtx.getxForwardedProto() >> 'https'
    iastCtx.getContentType() >> "text/html"
    final StackWalker stackWalker = Mock(StackWalker)
    final dependencies = new HasDependencies.Dependencies(
    Config.get(), reporter, overheadController, stackWalker
    )
    final handler = new RequestEndedHandler(dependencies)
    final TraceSegment traceSegment = Mock(TraceSegment)
    final reqCtx = Mock(RequestContext)
    reqCtx.getTraceSegment() >> traceSegment
    reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    final tags = Mock(Map<String, Object>)
    tags.get("http.url") >> "https://localhost/a"
    tags.get("http.status_code") >> 200i
    final spanInfo = Mock(IGSpanInfo)
    spanInfo.getTags() >> tags
    IastRequestContext.get(span) >> iastCtx


    when:
    def flow = handler.apply(reqCtx, spanInfo)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * iastCtx.getTaintedObjects() >> null
    1 * overheadController.releaseRequest()
    1 * spanInfo.getTags() >> tags
    1 * tags.get('http.url') >> "https://localhost/a"
    1 * tags.get('http.status_code') >> 200i
    1 * iastCtx.getStrictTransportSecurity()
    1 * tracer.activeSpan() >> span
    1 * iastCtx.getContentType() >> "text/html"
    1 * reporter.report(_, _ as Vulnerability) >> {
      savedVul1 = it[1]
    }

    with(savedVul1) {
      type == VulnerabilityType.HSTS_HEADER_MISSING
    }
  }


  void 'no hsts vulnerability reported'() {
    given:
    Vulnerability savedVul1
    final OverheadController overheadController = Mock(OverheadController)
    final iastCtx = Mock(IastRequestContext)
    iastCtx.getxForwardedProto() >> 'https'
    iastCtx.getContentType() >> "text/html"
    final StackWalker stackWalker = Mock(StackWalker)
    final dependencies = new HasDependencies.Dependencies(
    Config.get(), reporter, overheadController, stackWalker
    )
    final handler = new RequestEndedHandler(dependencies)
    final TraceSegment traceSegment = Mock(TraceSegment)
    final reqCtx = Mock(RequestContext)
    reqCtx.getTraceSegment() >> traceSegment
    reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    final tags = Mock(Map<String, Object>)
    tags.get("http.url") >> url
    tags.get("http.status_code") >> status
    final spanInfo = Mock(IGSpanInfo)
    spanInfo.getTags() >> tags
    IastRequestContext.get(span) >> iastCtx


    when:
    def flow = handler.apply(reqCtx, spanInfo)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * iastCtx.getTaintedObjects() >> null
    1 * overheadController.releaseRequest()
    1 * spanInfo.getTags() >> tags
    1 * tags.get('http.url') >> url
    1 * tags.get('http.status_code') >> status
    1 * iastCtx.getStrictTransportSecurity()
    0 * _

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
