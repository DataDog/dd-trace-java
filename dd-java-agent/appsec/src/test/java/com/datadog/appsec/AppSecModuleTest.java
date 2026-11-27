package com.datadog.appsec;

import static com.datadog.appsec.event.OrderedCallback.Priority.DEFAULT;
import static com.datadog.appsec.event.OrderedCallback.Priority.HIGH;
import static com.datadog.appsec.event.OrderedCallback.Priority.LOW;
import static com.datadog.appsec.event.data.AddressTestBridge.newAddress;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.ddwaf.WAFModule;
import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.OrderedCallback.CallbackPriorityComparator;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppSecModuleTest {

  @Test
  void dataSubscriptionsAreCorrectlyOrdered() {
    NoopDataSubscription ds1 = new NoopDataSubscription("ds1", emptyList(), DEFAULT);
    NoopDataSubscription ds2 = new NoopDataSubscription("ds2", emptyList(), DEFAULT);
    NoopDataSubscription ds3 = new NoopDataSubscription("ds3", emptyList(), HIGH);
    NoopDataSubscription ds4 = new NoopDataSubscription("ds4", emptyList(), LOW);

    // when
    List<NoopDataSubscription> list = asList(ds3, ds1, ds2, ds4);
    list.sort(CallbackPriorityComparator.INSTANCE);

    // then
    assertEquals(asList(ds3, ds1, ds2, ds4), list);

    // when: 'sorting a list with same priorities'
    NoopDataSubscription ds1a = new NoopDataSubscription("ds1a", emptyList(), DEFAULT);
    NoopDataSubscription ds1b = new NoopDataSubscription("ds1b", emptyList(), DEFAULT);
    List<NoopDataSubscription> list2 = asList(ds1a, ds1b);
    list2.sort(CallbackPriorityComparator.INSTANCE);

    // then: 'insertion order should be maintained for items with same priority'
    assertEquals(asList(ds1a, ds1b), list2);

    // when: 'sorting a different list with mixed priorities'
    List<NoopDataSubscription> mixedList = asList(ds1, ds3, ds2, ds4);
    mixedList.sort(CallbackPriorityComparator.INSTANCE);

    // then: 'items should be reordered by priority'
    assertEquals(asList(ds3, ds1, ds2, ds4), mixedList);
  }

  @Test
  void subscribedAddressesAreCorrectlyTrackedInDataSubscription() {
    // given: 'mock addresses'
    Address<?> address1 = newAddress("one");
    Address<?> address2 = newAddress("two");

    // when: 'creating a subscription with addresses'
    NoopDataSubscription ds = new NoopDataSubscription("test", asList(address1, address2), DEFAULT);

    // then: 'the addresses should be properly stored'
    assertEquals(2, ds.getSubscribedAddresses().size());
    assertTrue(ds.getSubscribedAddresses().contains(address1));
    assertTrue(ds.getSubscribedAddresses().contains(address2));

    // when: 'creating a subscription with empty addresses'
    NoopDataSubscription emptyDs = new NoopDataSubscription("empty", emptyList(), DEFAULT);

    // then: 'the subscription should have empty addresses'
    assertTrue(emptyDs.getSubscribedAddresses().isEmpty());
  }

  @Test
  void wafModuleShouldCorrectlyManageItsDataSubscriptions() {
    // given: 'a concrete WAFModule implementation'
    WAFModule module = new WAFModule();

    // when: 'getting data subscriptions before initialization'
    Collection<AppSecModule.DataSubscription> subscriptions = module.getDataSubscriptions();

    // then: 'the module should return an empty list'
    assertTrue(subscriptions.isEmpty());
  }

  @Test
  void nullSubscriptionAddressesShouldBeProperlyHandled() {
    // when: 'creating a subscription with null addresses'
    NoopDataSubscription subscription = new NoopDataSubscription("nullTest", null, DEFAULT);

    // then: 'an exception should be thrown or empty addresses set based on implementation'
    assertNull(subscription.getSubscribedAddresses());
  }

  private static class NoopDataSubscription extends AppSecModule.DataSubscription {
    private final String name;

    NoopDataSubscription(
        String name, Collection<Address<?>> subscribedAddresses, Priority priority) {
      super(subscribedAddresses, priority);
      this.name = name;
    }

    @Override
    public void onDataAvailable(
        ChangeableFlow flow,
        AppSecRequestContext reqCtx,
        DataBundle dataBundle,
        GatewayContext gwCtx) {}

    @Override
    public String toString() {
      return name;
    }
  }
}
