package com.datadog.appsec.gateway

import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.KnownAddresses
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.test.util.DDSpecification

class GatewayBridgeIGRegistrationSpecification extends DDSpecification {
  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()

  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher, null, [])

  void 'request_body_start and request_body_done are registered'() {
    given:
    1 * eventDispatcher.allSubscribedEvents() >> [EventType.REQUEST_BODY_START, EventType.REQUEST_BODY_END]
    1 * eventDispatcher.allSubscribedDataAddresses() >> []

    when:
    bridge.init()

    then:
    1 * ig.registerCallback(Events.REQUEST_BODY_START, _)
    1 * ig.registerCallback(Events.REQUEST_BODY_DONE, _)
  }

  void 'request_body_done is is registered via data address'() {
    given:
    1 * eventDispatcher.allSubscribedEvents() >> []
    1 * eventDispatcher.allSubscribedDataAddresses() >> [KnownAddresses.REQUEST_BODY_RAW]

    when:
    bridge.init()

    then:
    1 * ig.registerCallback(Events.REQUEST_BODY_DONE, _)
  }

  void 'request_body_start is not registered'() {
    given:
    1 * eventDispatcher.allSubscribedEvents() >> [EventType.REQUEST_BODY_END]
    1 * eventDispatcher.allSubscribedDataAddresses() >> []

    when:
    bridge.init()

    then:
    0 * ig.registerCallback(Events.REQUEST_BODY_START, _)
  }

  void 'request_body_end is not registered'() {
    given:
    1 * eventDispatcher.allSubscribedEvents() >> [EventType.REQUEST_BODY_START]
    1 * eventDispatcher.allSubscribedDataAddresses() >> []

    when:
    bridge.init()

    then:
    0 * ig.registerCallback(Events.REQUEST_BODY_DONE, _)
  }
}
