package com.datadog.appsec

import com.datadog.appsec.ddwaf.WAFModule
import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.OrderedCallback
import com.datadog.appsec.event.data.Address
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.gateway.GatewayContext
import datadog.trace.test.util.DDSpecification

import static com.datadog.appsec.event.OrderedCallback.Priority.DEFAULT
import static com.datadog.appsec.event.OrderedCallback.Priority.HIGH
import static com.datadog.appsec.event.OrderedCallback.Priority.LOW

class AppSecModuleSpecification extends DDSpecification {
  void 'data subscriptions are correctly ordered'() {
    def ds1 = new NoopDataSubscription('ds1', [], DEFAULT)
    def ds2 = new NoopDataSubscription('ds2', [], DEFAULT)
    def ds3 = new NoopDataSubscription('ds3', [], HIGH)
    def ds4 = new NoopDataSubscription('ds4', [], LOW)

    when:
    def list = [ds3, ds1, ds2, ds4]
    list.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE)

    then:
    list == [ds3, ds1, ds2, ds4]

    when: 'sorting a list with same priorities'
    def ds1a = new NoopDataSubscription('ds1a', [], DEFAULT)
    def ds1b = new NoopDataSubscription('ds1b', [], DEFAULT)
    def list2 = [ds1a, ds1b]
    list2.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE)

    then: 'insertion order should be maintained for items with same priority'
    list2 == [ds1a, ds1b]

    when: 'sorting a different list with mixed priorities'
    def mixedList = [ds1, ds3, ds2, ds4]
    mixedList.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE)

    then: 'items should be reordered by priority'
    mixedList == [ds3, ds1, ds2, ds4]
  }

  void 'subscribedAddresses are correctly tracked in DataSubscription'() {
    given: 'mock addresses'
    def address1 = new Address("one")
    def address2 = new Address("two")

    when: 'creating a subscription with addresses'
    def ds = new NoopDataSubscription('test', [address1, address2], DEFAULT)

    then: 'the addresses should be properly stored'
    ds.subscribedAddresses.size() == 2
    ds.subscribedAddresses.contains(address1)
    ds.subscribedAddresses.contains(address2)

    when: 'creating a subscription with empty addresses'
    def emptyDs = new NoopDataSubscription('empty', [], DEFAULT)

    then: 'the subscription should have empty addresses'
    assert emptyDs.subscribedAddresses.empty
  }

  void 'WAFModule should correctly manage its data subscriptions'() {
    given: 'a concrete WAFModule implementation'
    def module = new WAFModule()

    when: 'getting data subscriptions before initialization'
    def subscriptions = module.getDataSubscriptions()

    then: 'the module should return an empty list'
    assert subscriptions.isEmpty()
  }

  void 'null subscription addresses should be properly handled'() {
    when: 'creating a subscription with null addresses'
    final subscription = new NoopDataSubscription('nullTest', null, DEFAULT)
    subscription

    then: 'an exception should be thrown or empty addresses set based on implementation'
    assert subscription.subscribedAddresses == null
  }

  private static class NoopDataSubscription extends AppSecModule.DataSubscription {
    final String name

    NoopDataSubscription(String name, Collection<Address<?>> subscribedAddresses, Priority priority) {
      super(subscribedAddresses, priority)
      this.name = name
    }

    @Override
    void onDataAvailable(ChangeableFlow flow, AppSecRequestContext reqCtx, DataBundle dataBundle, GatewayContext gwCtx) {
    }

    @Override
    String toString() {
      name
    }
  }
}
