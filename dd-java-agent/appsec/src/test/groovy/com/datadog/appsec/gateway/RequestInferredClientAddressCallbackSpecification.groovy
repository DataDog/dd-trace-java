package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification


class RequestInferredClientAddressCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'set inferred ip'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    final cb = new RequestInferredClientAddressCallback()

    when:
    def flow = cb.apply(ctx, "1.1.1.1")

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * appSecCtx.setInferredClientIp("1.1.1.1")
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'does nothing if there is no appsec context'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    final cb = new RequestInferredClientAddressCallback()

    when:
    def flow = cb.apply(ctx, "1.1.1.1")

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> null
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }
}
