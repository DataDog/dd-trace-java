package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import datadog.trace.api.gateway.Flow
import datadog.trace.test.util.DDSpecification


class RequestStartedCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'produces appsec context and publishes event'() {
    given:
    EventProducerService eventProducer = Mock()
    final cb = new RequestStartedCallback(eventProducer)

    when:
    def startFlow = cb.get()

    then:
    1 * eventProducer.publishEvent(_ as AppSecRequestContext, EventType.REQUEST_START)
    0 * _
    def producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'returns null context if appsec is disabled'() {
    given:
    EventProducerService eventProducer = Mock()
    final cb = new RequestStartedCallback(eventProducer)
    AppSecSystem.active = false

    when:
    def startFlow = cb.get()

    then:
    0 * _
    Object producedCtx = startFlow.getResult()
    producedCtx == null

    cleanup:
    AppSecSystem.active = true
  }
}
