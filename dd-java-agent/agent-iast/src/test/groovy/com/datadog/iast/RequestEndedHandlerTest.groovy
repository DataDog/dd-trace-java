package com.datadog.iast

import com.datadog.iast.HasDependencies.Dependencies
import com.datadog.iast.overhead.OverheadController
import datadog.trace.api.Config
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackWalker
import groovy.transform.CompileDynamic

@CompileDynamic
class RequestEndedHandlerTest extends DDSpecification {

  def setup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'request ends with IAST context'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    final iastCtx = Mock(IastRequestContext)
    final StackWalker stackWalker = Mock(StackWalker)
    final dependencies = new Dependencies(
      Config.get(), new Reporter(), overheadController, stackWalker
      )
    final handler = new RequestEndedHandler(dependencies)
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
    1 * iastCtx.getTaintedObjects() >> null
    1 * overheadController.releaseRequest()
    0 * _
  }

  void 'request ends without IAST context'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    final StackWalker stackWalker = Mock(StackWalker)
    final dependencies = new Dependencies(
      Config.get(), new Reporter(), overheadController, stackWalker
      )
    final handler = new RequestEndedHandler(dependencies)
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
    1 * reqCtx.getTraceSegment() >> traceSegment
    1 * traceSegment.setTagTop("_dd.iast.enabled", 0)
    0 * overheadController.releaseRequest()
    0 * _
  }
}
