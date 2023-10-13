package com.datadog.appsec

import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.OrderedCallback
import com.datadog.appsec.event.data.Address
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.test.util.DDSpecification

import static com.datadog.appsec.event.OrderedCallback.Priority.DEFAULT
import static com.datadog.appsec.event.OrderedCallback.Priority.HIGH

class AppSecModuleSpecification extends DDSpecification {
  void 'data subscriptions are correctly ordered'() {
    def ds1 = new NoopDataSubscription('ds1', [], DEFAULT)
    def ds2 = new NoopDataSubscription('ds2', [], DEFAULT)
    def ds3 = new NoopDataSubscription('ds3', [], HIGH)

    when:
    def list = [ds3, ds1, ds2]
    list.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE)

    then:
    list == [ds3, ds1, ds2]
  }

  private static class NoopDataSubscription extends AppSecModule.DataSubscription {
    final String name

    NoopDataSubscription(String name, Collection<Address<?>> subscribedAddresses, Priority priority) {
      super(subscribedAddresses, priority)
      this.name = name
    }

    @Override
    void onDataAvailable(ChangeableFlow flow, AppSecRequestContext context, DataBundle dataBundle, boolean isTransient) {
    }

    @Override
    String toString() {
      name
    }
  }
}
