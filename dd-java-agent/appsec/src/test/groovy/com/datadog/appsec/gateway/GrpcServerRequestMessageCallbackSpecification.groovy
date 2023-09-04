package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification


class GrpcServerRequestMessageCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'receives transforms object and publishes'() {
    given:
    DataBundle bundle
    EventProducerService.DataSubscriberInfo dataSubscriberInfo = Mock()
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    EventProducerService producerService = Mock()
    final cb = new GrpcServerRequestMessageCallback(producerService)
    final obj = new Object() {
        @SuppressWarnings('UnusedPrivateField')
        private String foo = 'bar'
      }

    when:
    final flow = cb.apply(ctx, obj)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * producerService.getDataSubscribers({ KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE in it }) >> dataSubscriberInfo
    1 * dataSubscriberInfo.isEmpty() >> false
    1 * producerService.publishDataEvent(dataSubscriberInfo, appSecCtx,  _ as DataBundle, true) >>
    { a, b, db, c -> bundle = db; NoopFlow.INSTANCE }
    0 * _
    bundle.get(KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE) == [foo: 'bar']
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'does nothing if there is no appsec context'() {
    given:
    RequestContext ctx = Mock()
    EventProducerService producerService = Mock()
    final cb = new GrpcServerRequestMessageCallback(producerService)
    final obj = new Object() {
        @SuppressWarnings('UnusedPrivateField')
        private String foo = 'bar'
      }

    when:
    final flow = cb.apply(ctx, obj)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> null
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }
}
