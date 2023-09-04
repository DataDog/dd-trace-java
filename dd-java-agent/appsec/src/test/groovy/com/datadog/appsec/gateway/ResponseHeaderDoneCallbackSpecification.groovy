package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification


class ResponseHeaderDoneCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'happy path'() {
    given:
    DataBundle bundle
    EventProducerService.DataSubscriberInfo dataSubscriberInfo = Mock()
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    EventProducerService producerService = Mock()
    final cb = new ResponseHeaderDoneCallback(producerService)

    when:
    def flow = cb.apply(ctx)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * appSecCtx.isRespDataPublished() >> false
    1 * appSecCtx.finishResponseHeaders()
    _ * appSecCtx.getResponseStatus() >> 200
    1 * appSecCtx.isFinishedResponseHeaders() >> true
    1 * appSecCtx.getResponseHeaders() >> [foo: ['bar']]
    1 * producerService.getDataSubscribers({ KnownAddresses.RESPONSE_STATUS in it && KnownAddresses.RESPONSE_HEADERS_NO_COOKIES in it }) >> dataSubscriberInfo
    1 * producerService.publishDataEvent(dataSubscriberInfo, appSecCtx, _ as DataBundle, false) >>
    { a, b, db, c -> bundle = db; NoopFlow.INSTANCE }
    1 * appSecCtx.setRespDataPublished(true)
    0 * _
    bundle.get(KnownAddresses.RESPONSE_STATUS) == "200"
    bundle.get(KnownAddresses.RESPONSE_HEADERS_NO_COOKIES) == [foo: ['bar']]
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'does nothing if there is no appsec context'() {
    given:
    RequestContext ctx = Mock()
    EventProducerService producerService = Mock()
    final cb = new ResponseHeaderDoneCallback(producerService)

    when:
    def flow = cb.apply(ctx)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> null
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'do not publish without status code'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    EventProducerService producerService = Mock()
    final cb = new ResponseHeaderDoneCallback(producerService)

    when:
    def flow = cb.apply(ctx)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * appSecCtx.isRespDataPublished() >> false
    1 * appSecCtx.finishResponseHeaders()
    1 * appSecCtx.getResponseStatus() >> 0
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }
}
