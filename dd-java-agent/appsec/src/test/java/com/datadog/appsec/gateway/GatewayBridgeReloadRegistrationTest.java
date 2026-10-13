package com.datadog.appsec.gateway;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

  @Test
  void consecutiveReloadsDoNotRegisterCallbacksTwice() {
    // startup: no ruleset requires the conditional addresses
    producerService.subscribedAddresses = emptyList();
    bridge.init();

    // reload: the new ruleset requires request path params and the request body object
    producerService.subscribedAddresses =
        asList(KnownAddresses.REQUEST_PATH_PARAMS, KnownAddresses.REQUEST_BODY_OBJECT);
    reload();

    Object pathParamsCallback = callbackProvider.getCallback(EVENTS.requestPathParams());
    Object bodyProcessedCallback = callbackProvider.getCallback(EVENTS.requestBodyProcessed());
    assertNotNull(pathParamsCallback);
    assertNotNull(bodyProcessedCallback);

    // AppSec triggers the reload twice per Remote Config update (WAFModule and
    // AppSecConfigServiceImpl), this time with a growing address set
    producerService.subscribedAddresses =
        asList(
            KnownAddresses.REQUEST_PATH_PARAMS,
            KnownAddresses.REQUEST_BODY_OBJECT,
            KnownAddresses.REQUEST_FILES_FILENAMES);
    assertDoesNotThrow(this::reload);

    // already registered callbacks are left untouched, the new one is registered
    assertSame(pathParamsCallback, callbackProvider.getCallback(EVENTS.requestPathParams()));
    assertSame(bodyProcessedCallback, callbackProvider.getCallback(EVENTS.requestBodyProcessed()));
    assertNotNull(callbackProvider.getCallback(EVENTS.requestFilesFilenames()));
    assertNull(callbackProvider.getCallback(EVENTS.requestFilesContent()));
  }

  @Test
  void enabledInactiveRegistersCallbacksOnActivation() {
    // ENABLED_INACTIVE: the WAF module has no ruleset loaded, so it exposes no data subscription
    // and no address at all is subscribed at startup
    producerService.subscribedAddresses = emptyList();
    bridge.init();

    assertNull(callbackProvider.getCallback(EVENTS.requestPathParams()));
    assertNull(callbackProvider.getCallback(EVENTS.requestBodyProcessed()));
    assertNull(callbackProvider.getCallback(EVENTS.requestFilesFilenames()));
    assertNull(callbackProvider.getCallback(EVENTS.requestFilesContent()));

    // activation: Remote Config delivers a ruleset requiring every conditional address, plus the
    // raw request body, whose events are already registered unconditionally by init()
    producerService.subscribedAddresses =
        asList(
            KnownAddresses.REQUEST_PATH_PARAMS,
            KnownAddresses.REQUEST_BODY_OBJECT,
            KnownAddresses.REQUEST_FILES_FILENAMES,
            KnownAddresses.REQUEST_FILES_CONTENT,
            KnownAddresses.REQUEST_BODY_RAW);
    assertDoesNotThrow(this::reload);

    assertNotNull(callbackProvider.getCallback(EVENTS.requestPathParams()));
    assertNotNull(callbackProvider.getCallback(EVENTS.requestBodyProcessed()));
    assertNotNull(callbackProvider.getCallback(EVENTS.requestFilesFilenames()));
    assertNotNull(callbackProvider.getCallback(EVENTS.requestFilesContent()));
  }

  private void reload() {
    bridge.registerAdditionalIGCallbacksIfNeeded(
        GatewayBridge.additionalIGEventTypes(producerService.allSubscribedDataAddresses()));
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
