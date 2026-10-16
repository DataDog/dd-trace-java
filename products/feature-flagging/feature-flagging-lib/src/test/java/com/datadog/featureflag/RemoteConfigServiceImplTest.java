package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RemoteConfigServiceImplTest {
  private static final byte[] DOCUMENT = "{\"flags\":{}}".getBytes(UTF_8);

  @Test
  @SuppressWarnings("unchecked")
  void subscriptionRegistersTheProductWithARawDeserializer() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);

    service.subscribe(content -> {});
    service.subscribe(content -> {});

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    final ArgumentCaptor<ConfigurationDeserializer<byte[]>> deserializer =
        ArgumentCaptor.forClass(ConfigurationDeserializer.class);
    verify(poller).addListener(eq(Product.FFE_FLAGS), deserializer.capture(), eq(service));
    verify(poller).start();
    assertArrayEquals(DOCUMENT, deserializer.getValue().deserialize(DOCUMENT));
  }

  @Test
  void forwardsDocumentsAndRemovalsToAllSubscribers() {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    final List<byte[]> first = new ArrayList<>();
    final List<byte[]> second = new ArrayList<>();
    service.subscribe(first::add);
    service.subscribe(second::add);

    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));
    service.accept("key", null, mock(PollingRateHinter.class));

    assertEquals(2, first.size());
    assertArrayEquals(DOCUMENT, first.get(0));
    assertNull(first.get(1));
    assertEquals(2, second.size());
  }

  @Test
  void replaysTheCurrentDocumentToLateSubscribers() {
    final RemoteConfigServiceImpl service =
        new RemoteConfigServiceImpl(mock(ConfigurationPoller.class));
    service.subscribe(content -> {});
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    final List<byte[]> late = new ArrayList<>();
    service.subscribe(late::add);

    assertEquals(1, late.size());
    assertArrayEquals(DOCUMENT, late.get(0));
  }

  @Test
  void startRegistersTheProductOnceBeforeAnySubscription() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);

    service.start();
    service.start();
    service.subscribe(content -> {});

    verify(poller, times(1)).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller, times(1)).start();
  }

  @Test
  void unsubscribedSubscribersStopReceivingDocuments() throws Exception {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> received = new ArrayList<>();
    final AutoCloseable subscription = service.subscribe(received::add);

    subscription.close();
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    assertEquals(0, received.size());
    verify(poller, never()).removeListeners(any());
  }

  @Test
  void closeUnregistersTheProductAndDropsSubscribers() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    final RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(poller);
    final List<byte[]> received = new ArrayList<>();
    service.subscribe(received::add);

    service.close();
    service.close();
    service.accept("key", DOCUMENT, mock(PollingRateHinter.class));

    verify(poller, times(1))
        .removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller, times(1)).removeListeners(Product.FFE_FLAGS);
    verify(poller, never()).stop();
    assertEquals(0, received.size());
  }
}
