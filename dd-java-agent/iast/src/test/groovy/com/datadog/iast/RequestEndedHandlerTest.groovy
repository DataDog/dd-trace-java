package com.datadog.iast


import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.TraceSegment
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification

class RequestEndedHandlerTest extends DDSpecification {

  void 'request ends with IAST context'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    final handler = new RequestEndedHandler(overheadController)
    final iastCtx = Mock(IastRequestContext)
    final TraceSegment traceSegment = Mock(TraceSegment)
    final reqCtx = Mock(RequestContext)
    reqCtx.getTraceSegment() >> traceSegment
    reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    final spanInfo = Mock(IGSpanInfo)

    when:
    def flow = handler.apply(reqCtx, spanInfo)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * reqCtx.getData(RequestContextSlot.IAST) >> iastCtx
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    1 * overheadController.releaseRequest()
    0 * _
  }

  void 'request ends without IAST context'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    final handler = new RequestEndedHandler(overheadController)
    final TraceSegment traceSegment = Mock(TraceSegment)
    final reqCtx = Mock(RequestContext)
    reqCtx.getTraceSegment() >> traceSegment
    final spanInfo = Mock(IGSpanInfo)

    when:
    def flow = handler.apply(reqCtx, spanInfo)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * reqCtx.getData(RequestContextSlot.IAST) >> null
    0 * overheadController.releaseRequest()
    0 * _
  }
}
