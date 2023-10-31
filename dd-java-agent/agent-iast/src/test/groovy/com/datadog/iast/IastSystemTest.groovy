package com.datadog.iast

import com.datadog.iast.telemetry.TelemetryRequestStartedHandler
import com.datadog.iast.telemetry.TelemetryRequestEndedHandler
import datadog.trace.api.gateway.*
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.api.internal.TraceSegment
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
    final iastContext = Mock(IastRequestContext)
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
    1 * ss.registerCallback(Events.get().grpcServerRequestMessage(), _)
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

  void 'check telemetry start'() {
    setup:
    injectSysConfig('dd.iast.telemetry.verbosity', verbosity.name())
    final ss = Mock(SubscriptionService)

    when:
    IastSystem.start(ss)

    then:
    1 * ss.registerCallback(Events.get().requestStarted(), {
      final hasTelemetry = it instanceof TelemetryRequestStartedHandler
      return hasTelemetry == (verbosity != Verbosity.OFF)
    })
    1 * ss.registerCallback(Events.get().requestEnded(), {
      final hasTelemetry = it instanceof TelemetryRequestEndedHandler
      return hasTelemetry == (verbosity != Verbosity.OFF)
    })

    where:
    verbosity << Verbosity.values()
  }
}
