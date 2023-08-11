package com.datadog.iast

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.test.util.DDSpecification

class IastSystemTest extends DDSpecification {

  def setup() {
    InstrumentationBridge.clearIastModules()
  }

  void 'start'() {
    given:
    final ig = new InstrumentationGateway()
    final ss = Spy(ig.getSubscriptionService(RequestContextSlot.IAST))
    final cbp = ig.getCallbackProvider(RequestContextSlot.IAST)
    final traceSegment = Mock(TraceSegment)
    final iastContext = Mock(IastRequestContext) {
      getTaintedObjects() >> null
    }
    final RequestContext reqCtx = Stub(RequestContext) {
      getTraceSegment() >> traceSegment
      getData(RequestContextSlot.IAST) >> iastContext
    }
    final igSpanInfo = Mock(IGSpanInfo)


    when:
    IastSystem.start(ss)

    then:
    1 * ss.registerCallback(Events.get().requestStarted(), _)
    1 * ss.registerCallback(Events.get().requestEnded(), _)
    1 * ss.registerCallback(Events.get().requestHeader(), _)
    0 * _

    when:
    final startCallback = cbp.getCallback(Events.get().requestStarted())
    final endCallback = cbp.getCallback(Events.get().requestEnded())

    then:
    startCallback != null
    endCallback != null
    0 * _

    when:
    startCallback.get()
    endCallback.apply(reqCtx, igSpanInfo)

    then:
    1 * iastContext.getTaintedObjects()
    1 * iastContext.getMetricCollector()
    1 * traceSegment.setTagTop('_dd.iast.enabled', 1)
    1 * iastContext.getxContentTypeOptions() >> 'nosniff'
    1 * iastContext.getStrictTransportSecurity() >> 'max-age=35660'
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
