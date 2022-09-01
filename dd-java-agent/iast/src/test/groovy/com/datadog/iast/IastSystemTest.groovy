package com.datadog.iast

import datadog.trace.api.TraceSegment
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.test.util.DDSpecification

class IastSystemTest extends DDSpecification {

  void 'start'() {
    given:
    final ig = new InstrumentationGateway()
    final ss = Spy(ig.getSubscriptionService(RequestContextSlot.IAST))
    final cbp = ig.getCallbackProvider(RequestContextSlot.IAST)
    final traceSegment= Mock(TraceSegment)
    final reqCtx = Mock(RequestContext)
    reqCtx.getTraceSegment() >> traceSegment
    final igSpanInfo = Mock(IGSpanInfo)


    when:
    IastSystem.start(ss)

    then:
    1 * ss.registerCallback(Events.get().requestStarted(), _)
    1 * ss.registerCallback(Events.get().requestEnded(), _)

    when:
    final startCallback = cbp.getCallback(Events.get().requestStarted())
    final endCallback = cbp.getCallback(Events.get().requestEnded())

    then:
    startCallback != null
    endCallback != null

    when:
    startCallback.get()
    endCallback.apply(reqCtx, igSpanInfo)

    then:
    1 * traceSegment.setTagTop('_dd.iast.enabled', 1)
    _ * reqCtx._
    0 * _
    noExceptionThrown()
  }

  void 'start disabled'() {
    setup:
    injectSysConfig('dd.iast.enabled', "false")
    rebuildConfig()
    final ss = Mock(SubscriptionService)

    when:
    IastSystem.start(ss)

    then:
    0 * _
  }
}
