package com.datadog.appsec

import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.OrderedCallback
import com.datadog.appsec.event.data.Address
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import spock.lang.Specification

class AppSecModuleSpecification extends Specification {
  void 'data subscriptions are correctly ordered'() {
    def ds1 = new NoopDataSubscription([], 0)
    def ds2 = new NoopDataSubscription([], 1)
    def ds3 = new NoopDataSubscription([], 1)

    when:
    def list = [ds3, ds2, ds1]
    list.sort(OrderedCallback.OrderedCallbackComparator.INSTANCE)

    then:
    list == [ds1, ds2, ds3]
  }

  void 'event subscriptions are correctly ordered'() {
    def es1 = new NoopEventSubscription(EventType.REQUEST_START, 0)
    def es2 = new NoopEventSubscription(EventType.REQUEST_START, 1)
    def es3 = new NoopEventSubscription(EventType.REQUEST_START, 1)

    when:
    def list = [es3, es2, es1]
    list.sort(OrderedCallback.OrderedCallbackComparator.INSTANCE)

    then:
    list == [es1, es2, es3]
  }

  private static class NoopDataSubscription extends AppSecModule.DataSubscription {
    NoopDataSubscription(Collection<Address<?>> subscribedAddresses, int priority) {
      super(subscribedAddresses, priority)
    }

    @Override
    void onDataAvailable(ChangeableFlow flow, AppSecRequestContext context, DataBundle dataBundle) {
    }
  }

  private static class NoopEventSubscription extends AppSecModule.EventSubscription {
    NoopEventSubscription(EventType eventType, int priority) {
      super(eventType, priority)
    }

    @Override
    void onEvent(ChangeableFlow flow, AppSecRequestContext ctx, EventType eventType) {
    }
  }
}
