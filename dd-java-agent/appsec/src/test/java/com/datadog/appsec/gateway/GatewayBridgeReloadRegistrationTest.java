package com.datadog.appsec.gateway;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the conditionally registered instrumentation gateway callbacks are re-evaluated
 * when the AppSec configuration is reloaded (Remote Config), using a real {@link
 * InstrumentationGateway} so that a double registration surfaces as a real {@link
 * IllegalStateException}.
 */
class GatewayBridgeReloadRegistrationTest {

  private InstrumentationGateway instrumentationGateway;
  private CallbackProvider callbackProvider;
  private StubEventProducerService producerService;
  private GatewayBridge bridge;

  @BeforeEach
  void setUp() {
    instrumentationGateway = new InstrumentationGateway();
    callbackProvider = instrumentationGateway.getCallbackProvider(RequestContextSlot.APPSEC);
    SubscriptionService subscriptionService =
        instrumentationGateway.getSubscriptionService(RequestContextSlot.APPSEC);
    producerService = new StubEventProducerService();
    bridge = new GatewayBridge(subscriptionService, producerService, () -> null, null, emptyList());
  }

  @Test
  void reloadRegistersCallbacksNotRequiredAtStartup() {
    // startup: no ruleset requires the conditional addresses
    producerService.subscribedAddresses = emptyList();
    bridge.init();

    assertNull(callbackProvider.getCallback(EVENTS.requestPathParams()));

    // reload: the new ruleset requires request path params
    producerService.subscribedAddresses = singletonList(KnownAddresses.REQUEST_PATH_PARAMS);
    bridge.registerAdditionalIGCallbacksIfNeeded(
        GatewayBridge.additionalIGEventTypes(producerService.allSubscribedDataAddresses()));

    assertNotNull(callbackProvider.getCallback(EVENTS.requestPathParams()));
  }

  /**
   * Minimal {@link EventProducerService} whose set of subscribed addresses can be changed to
   * simulate a configuration reload. Only {@link #allSubscribedDataAddresses()} is exercised by the
   * registration logic under test.
   */
  private static final class StubEventProducerService implements EventProducerService {

    private Collection<Address<?>> subscribedAddresses = emptyList();

    @Override
    public DataSubscriberInfo getDataSubscribers(Address<?>... newAddresses) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Flow<Void> publishDataEvent(
        DataSubscriberInfo subscribers,
        AppSecRequestContext appSecRequestContext,
        DataBundle newData,
        GatewayContext gatewayContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Address<?>> allSubscribedDataAddresses() {
      return subscribedAddresses;
    }
  }
}
