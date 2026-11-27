package com.datadog.appsec.gateway;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.event.data.KnownAddresses;
import datadog.trace.api.gateway.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayBridgeIGRegistrationTest {

  private SubscriptionService ig;
  private EventDispatcher eventDispatcher;
  private GatewayBridge bridge;

  @BeforeEach
  void setUp() {
    ig = mock(SubscriptionService.class);
    eventDispatcher = mock(EventDispatcher.class);
    bridge = new GatewayBridge(ig, eventDispatcher, null, null, emptyList());
  }

  @Test
  void requestBodyStartAndRequestBodyDoneAreRegistered() {
    // given
    when(eventDispatcher.allSubscribedDataAddresses()).thenReturn(emptyList());

    // when
    bridge.init();

    // then
    verify(eventDispatcher).allSubscribedDataAddresses();
    verify(ig).registerCallback(eq(EVENTS.requestBodyStart()), any());
    verify(ig).registerCallback(eq(EVENTS.requestBodyDone()), any());
  }

  @Test
  void requestBodyDoneIsRegisteredViaDataAddress() {
    // given
    when(eventDispatcher.allSubscribedDataAddresses())
        .thenReturn(singletonList(KnownAddresses.REQUEST_BODY_RAW));

    // when
    bridge.init();

    // then
    verify(eventDispatcher).allSubscribedDataAddresses();
    verify(ig).registerCallback(eq(EVENTS.requestBodyDone()), any());
  }

  @Test
  void requestFilesContentIsRegisteredViaDataAddress() {
    // given
    when(eventDispatcher.allSubscribedDataAddresses())
        .thenReturn(singletonList(KnownAddresses.REQUEST_FILES_CONTENT));

    // when
    bridge.init();

    // then
    verify(eventDispatcher).allSubscribedDataAddresses();
    verify(ig).registerCallback(eq(EVENTS.requestFilesContent()), any());
  }
}
