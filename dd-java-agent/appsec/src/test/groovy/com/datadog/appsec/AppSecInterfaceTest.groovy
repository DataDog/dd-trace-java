package com.datadog.appsec

import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class AppSecInterfaceTest {

  private static class TestCallback implements Callback {

    private final Collection<Address> addresses

    TestCallback(Collection<Address> addresses) {
      this.addresses = addresses
    }

    @Override
    Set<Address> getRequiredAddresses() {
      return addresses
    }

    @Override
    Flow.ResultFlow onDataAvailable(RequestContext ctx, DataBundle dataBundle, boolean transientData) {
      return null
    }
  }

  @Test
  void 'subscribe callbacks with same Addresses'() {
    def appSecInterface = new AppSecInterface()

    Callback cb1 = new TestCallback([
          KnownAddresses.REQUEST_URI_RAW,
          KnownAddresses.REQUEST_HEADERS
        ])

    Callback cb2 = new TestCallback([
          KnownAddresses.REQUEST_HEADERS
        ])

    appSecInterface.subscribeCallback(cb1)
    appSecInterface.subscribeCallback(cb2)

    assertThat(appSecInterface.subscriptions).containsKeys(
      KnownAddresses.REQUEST_URI_RAW,
      KnownAddresses.REQUEST_HEADERS
    )
    assertThat(appSecInterface.subscriptions.get(KnownAddresses.REQUEST_URI_RAW)).containsOnly(cb1)
    assertThat(appSecInterface.subscriptions.get(KnownAddresses.REQUEST_HEADERS)).containsExactly(cb1, cb2)

  }

  @Test
  void 'callback with null addresses should not subscribe'() {
    def appSecInterface = new AppSecInterface()
    appSecInterface.subscribeCallback(new TestCallback(null))

    assertThat(appSecInterface.subscriptions).isEmpty()
  }

  @Test
  void 'callback with empty addresses should not subscribe'() {
    def appSecInterface = new AppSecInterface()
    appSecInterface.subscribeCallback(new TestCallback([]))

    assertThat(appSecInterface.subscriptions).isEmpty()
  }

  @Test
  void 'subscribe same callback few times should not produce duplications '() {
    def appSecInterface = new AppSecInterface()
    Callback cb = new TestCallback([KnownAddresses.REQUEST_URI_RAW])

    appSecInterface.subscribeCallback(cb)
    appSecInterface.subscribeCallback(cb)

    assertThat(appSecInterface.subscriptions).hasSize(1)
    assertThat(appSecInterface.subscriptions).containsOnlyKeys(KnownAddresses.REQUEST_URI_RAW)
    assertThat(appSecInterface.subscriptions.get(KnownAddresses.REQUEST_URI_RAW)).is(cb)
  }

  @Test
  void 'unsubscribe callback'() {
    def appSecInterface = new AppSecInterface()

    // Add subscribe callbacks
    Callback cb1 = new TestCallback([
      KnownAddresses.REQUEST_URI_RAW,
      KnownAddresses.REQUEST_HEADERS
    ])

    Callback cb2 = new TestCallback([
      KnownAddresses.REQUEST_HEADERS
    ])
    appSecInterface.subscribeCallback(cb1)
    appSecInterface.subscribeCallback(cb2)

    // Remove only cb1
    appSecInterface.unsubscribeCallback(cb1)

    assertThat(appSecInterface.subscriptions).hasSize(1)
    assertThat(appSecInterface.subscriptions).containsOnlyKeys(KnownAddresses.REQUEST_HEADERS)
    assertThat(appSecInterface.subscriptions.get(KnownAddresses.REQUEST_HEADERS)).is(cb2)

    // Remove cb2
    appSecInterface.unsubscribeCallback(cb2)
    assertThat(appSecInterface.subscriptions).isEmpty()
  }
}
