package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
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
    final cb = new RequestStartedCallback()

    when:
    def startFlow = cb.get()

    then:
    0 * _
    def producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'returns null context if appsec is disabled'() {
    given:
    final cb = new RequestStartedCallback()
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
