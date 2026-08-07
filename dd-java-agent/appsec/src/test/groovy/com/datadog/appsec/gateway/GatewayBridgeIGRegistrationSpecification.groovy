package com.datadog.appsec.gateway

import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.data.KnownAddresses
import datadog.trace.api.gateway.EventType
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.test.util.DDSpecification

class GatewayBridgeIGRegistrationSpecification extends DDSpecification {
  SubscriptionService ig = Mock()
  EventDispatcher eventDispatcher = Mock()

  GatewayBridge bridge = new GatewayBridge(ig, eventDispatcher, null, null, [])

  void 'request_body_start and request_body_done are registered'() {
    given:
    1 * eventDispatcher.allSubscribedDataAddresses() >> []

    when:
    bridge.init()

    then:
    1 * ig.registerCallback(Events.REQUEST_BODY_START, _)
    1 * ig.registerCallback(Events.REQUEST_BODY_DONE, _)
  }

  void 'request_body_done is is registered via data address'() {
    given:
    1 * eventDispatcher.allSubscribedDataAddresses() >> [KnownAddresses.REQUEST_BODY_RAW]

    when:
    bridge.init()

    then:
    1 * ig.registerCallback(Events.REQUEST_BODY_DONE, _)
  }

  void 'requestFilesContent is registered via data address'() {
    given:
    1 * eventDispatcher.allSubscribedDataAddresses() >> [KnownAddresses.REQUEST_FILES_CONTENT]

    when:
    bridge.init()

    then:
    1 * ig.registerCallback(Events.get().requestFilesContent(), _)
  }

  // Coherence test: every entry in IGAppSecEventDependencies.DATA_DEPENDENCIES must have its
  // events registered in GatewayBridge.init() when the corresponding address is subscribed.
  // If you add a new DATA_DEPENDENCIES entry without the matching registerCallback() call in
  // init(), this test fails with a message showing which event was missing.
  void 'DATA_DEPENDENCIES: events for address #addressKey are registered when address is subscribed'() {
    given:
    List<EventType<?>> registeredEvents = []
    SubscriptionService mockSvc = Mock()
    EventDispatcher mockDispatcher = Mock()
    _ * mockDispatcher.allSubscribedDataAddresses() >> [address]
    mockSvc.registerCallback(_, _) >> { EventType et, Object cb -> registeredEvents << et }

    GatewayBridge testBridge = new GatewayBridge(mockSvc, mockDispatcher, null, null, [])
    testBridge.init()

    expect:
    events.every { event ->
      assert registeredEvents.contains(event),
        "Event '${event.type}' not registered for address '${address.key}'. " +
        "Add registerCallback(EVENTS.${event.type}, ...) to GatewayBridge.init() " +
        "inside an additionalIGEvents.contains() guard."
      true
    }

    where:
    [address, events] << dataDependenciesEntries()
    addressKey = address.key
  }

  private static List<List> dataDependenciesEntries() {
    def innerClass = GatewayBridge.declaredClasses.find { it.simpleName == 'IGAppSecEventDependencies' }
    def field = innerClass.getDeclaredField('DATA_DEPENDENCIES')
    field.accessible = true
    Map map = (Map) field.get(null)
    return map.collect { addr, evts -> [addr, evts as List] }
  }
}
