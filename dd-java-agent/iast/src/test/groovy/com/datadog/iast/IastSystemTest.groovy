package com.datadog.iast


import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.test.util.DDSpecification

class IastSystemTest extends DDSpecification {

  void 'start'() {
    given:
    final ig = new InstrumentationGateway()
    final ss = Spy(ig.getSubscriptionService(RequestContextSlot.IAST))
    final cbp = ig.getCallbackProvider(RequestContextSlot.IAST)

    when:
    IastSystem.start(ss)

    then:
    1 * ss.registerCallback(Events.get().requestStarted(), _)
    0 * _

    when:
    final startCallback = cbp.getCallback(Events.get().requestStarted())
    final endCallback = cbp.getCallback(Events.get().requestEnded())

    then:
    startCallback != null
    endCallback == null

    when:
    startCallback.get()

    then:
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
